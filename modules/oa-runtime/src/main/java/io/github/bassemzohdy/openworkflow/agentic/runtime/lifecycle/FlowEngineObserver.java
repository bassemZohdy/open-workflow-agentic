package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.quarkus.arc.Unremovable;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.WorkflowContextData;
import io.serverlessworkflow.impl.WorkflowException;
import io.serverlessworkflow.impl.lifecycle.WorkflowCancelledEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowFailedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowStatusEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.jboss.logging.Logger;

/**
 * The Quarkus Flow lifecycle listener that feeds {@link EngineStatusSync} (WP-105c, scope item 3). Flow binds every
 * CDI bean of type {@link WorkflowExecutionListener} into the {@code WorkflowApplication} when it is built
 * (bytecode of {@code WorkflowApplicationCreator}, 1.2.0), so no registration is needed. {@link Unremovable} keeps the
 * bean in deployments that do not otherwise inject it.
 *
 * <p>The listener only reports. An exception here would reach the engine's thread, so each handler catches and logs
 * failures; the reconciler retries locally buffered cancellation events; process loss can still lose the evidence.
 */
@Singleton
@Unremovable
public class FlowEngineObserver implements WorkflowExecutionListener {

    private static final Logger LOG = Logger.getLogger(FlowEngineObserver.class);

    // Best-effort local retry only: a process crash can still lose these reports. Bounded to avoid an
    // unbounded heap when the registry is unavailable; durable outcome recovery remains a separate requirement.
    private static final int MAX_PENDING_CANCELLATIONS = 1024;
    private final Set<String> pendingCancellations = new LinkedHashSet<>();
    private final EngineStatusSync sync;
    private final Map<WorkflowContextData, Boolean> cancelledFaults = Collections.synchronizedMap(new WeakHashMap<>());

    @Inject
    public FlowEngineObserver(EngineStatusSync sync) {
        this.sync = sync;
    }

    @Override
    public void onWorkflowStatusChanged(WorkflowStatusEvent event) {
        // The pinned SDK can turn cancellation of a timed task into a timeout-shaped failure. Its cancellation
        // sets CANCELLED first; defer this particular transition until the failed event reveals the actual cause.
        if (event.previousStatus() == WorkflowStatus.CANCELLED && event.status() == WorkflowStatus.FAULTED) {
            cancelledFaults.put(event.workflowContext(), Boolean.TRUE);
            return;
        }
        report(event, toEngine(event.status()));
    }

    @Override
    public void onWorkflowCompleted(WorkflowCompletedEvent event) {
        report(event, EngineStatus.COMPLETED);
    }

    @Override
    public void onWorkflowFailed(WorkflowFailedEvent event) {
        boolean followedCancellation = cancelledFaults.remove(event.workflowContext()) != null;
        if (followedCancellation && cancellationWrappedAsTimeout(event.cause())) {
            LOG.debug("ignoring SDK timeout produced by a cancelled task; awaiting the cancellation report");
            return;
        }
        // The registry keeps no reason for a fault; the engine's cause goes to the log, truncated.
        Throwable cause = event.cause();
        if (cause != null) {
            String text = String.valueOf(cause.getMessage());
            LOG.warnf("engine instance faulted: %s: %s", cause.getClass().getName(),
                    text.length() > 300 ? text.substring(0, 300) : text);
        }
        report(event, EngineStatus.FAULTED);
    }

    @Override
    public void onWorkflowCancelled(WorkflowCancelledEvent event) {
        String id;
        try {
            id = String.valueOf(event.workflowContext().instanceData().id());
        } catch (RuntimeException unreadable) {
            LOG.warnf(unreadable, "cancellation event without an instance id was not reported");
            return;
        }
        if (!reportCancellation(id)) {
            synchronized (pendingCancellations) {
                if (pendingCancellations.size() < MAX_PENDING_CANCELLATIONS || pendingCancellations.contains(id)) {
                    pendingCancellations.add(id);
                } else {
                    LOG.errorf("cancellation retry buffer full; outcome for %s needs durable recovery", id);
                }
            }
        }
    }

    /** Retries only actual cancellation events, never a cancellation request or a polled status. */
    void retryCancellationReports() {
        List<String> pending;
        synchronized (pendingCancellations) { pending = List.copyOf(pendingCancellations); }
        for (String id : pending) {
            if (reportCancellation(id)) {
                synchronized (pendingCancellations) { pendingCancellations.remove(id); }
            }
        }
    }

    private boolean reportCancellation(String id) {
        try {
            // A prepared instance can be discarded before it has a registry row. Retrying NO_ROW forever
            // would let such orphan events fill the bounded buffer and crowd out actual storage failures.
            sync.observe(id, EngineStatus.CANCELLED, "observed");
            return true;
        } catch (RuntimeException failed) {
            LOG.errorf(failed, "registry could not record engine status CANCELLED for instance %s", id);
            return false;
        }
    }

    private static boolean cancellationWrappedAsTimeout(Throwable cause) {
        if (!(cause instanceof WorkflowException workflow) || workflow.getWorkflowError() == null
                || !"https://serverlessworkflow.io/spec/1.0.0/errors/timeout".equals(workflow.getWorkflowError().type()))
            return false;
        Throwable nested = workflow.getCause();
        for (int depth = 0; depth < 8 && nested instanceof CompletionException; depth++) nested = nested.getCause();
        return nested instanceof CancellationException;
    }

    private void report(WorkflowEvent event, EngineStatus status) {
        if (status == null) {
            return;
        }
        String engineInstanceId;
        try {
            engineInstanceId = String.valueOf(event.workflowContext().instanceData().id());
        } catch (RuntimeException unreadable) {
            LOG.warnf(unreadable, "engine event %s without an instance id was not reported", event.type());
            return;
        }
        try {
            sync.observe(engineInstanceId, status, "observed");
        } catch (RuntimeException failed) {
            // The poll in the reconciler repairs a missed report; the engine must not see this exception.
            LOG.errorf(failed, "registry could not record engine status %s for instance %s", status, engineInstanceId);
        }
    }

    /** Maps Flow's status to ours; a status the registry has no state for maps to null (not reported). */
    static EngineStatus toEngine(WorkflowStatus status) {
        return switch (status) {
            case PENDING -> EngineStatus.PENDING;
            case RUNNING -> EngineStatus.RUNNING;
            case WAITING -> EngineStatus.WAITING;
            case COMPLETED -> EngineStatus.COMPLETED;
            case FAULTED -> EngineStatus.FAULTED;
            case CANCELLED -> EngineStatus.CANCELLED;
            case SUSPENDED -> EngineStatus.SUSPENDED;
        };
    }
}
