package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.quarkus.arc.Unremovable;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.lifecycle.WorkflowCancelledEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowFailedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowStatusEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * The Quarkus Flow lifecycle listener that feeds {@link EngineStatusSync} (WP-105c, scope item 3). Flow binds every
 * CDI bean of type {@link WorkflowExecutionListener} into the {@code WorkflowApplication} when it is built
 * (bytecode of {@code WorkflowApplicationCreator}, 1.2.0), so no registration is needed. {@link Unremovable} keeps the
 * bean, which nothing injects.
 *
 * <p>The listener only reports. An exception here would reach the engine's thread, so each handler catches and logs
 * failures; the reconciler's poll repairs whatever a failed report left behind.
 */
@Singleton
@Unremovable
public class FlowEngineObserver implements WorkflowExecutionListener {

    private static final Logger LOG = Logger.getLogger(FlowEngineObserver.class);

    private final EngineStatusSync sync;

    @Inject
    public FlowEngineObserver(EngineStatusSync sync) {
        this.sync = sync;
    }

    @Override
    public void onWorkflowStatusChanged(WorkflowStatusEvent event) {
        report(event, toEngine(event.status()));
    }

    @Override
    public void onWorkflowCompleted(WorkflowCompletedEvent event) {
        report(event, EngineStatus.COMPLETED);
    }

    @Override
    public void onWorkflowFailed(WorkflowFailedEvent event) {
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
        report(event, EngineStatus.CANCELLED);
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
