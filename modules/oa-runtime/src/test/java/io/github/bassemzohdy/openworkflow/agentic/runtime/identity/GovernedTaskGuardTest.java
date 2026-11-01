package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GovernedTaskGuard.Ungoverned;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Decision;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Outcome;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.lifecycle.TaskCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.TaskStartedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The guard against the REAL Flow engine: a thrown listener exception must fail the task and stop later tasks. */
class GovernedTaskGuardTest {
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Subject USER = new Subject(SubjectKind.USER, "alice");

    private final Ulid instanceId = UlidGenerator.shared().next();
    private final InstanceAuthority delegated = new InstanceAuthority(instanceId, "t1", USER,
            UlidGenerator.shared().next(), "corr-1", InstanceAuthority.State.AUTHORIZED, null, NOW, NOW);
    private Optional<InstanceAuthority> registered;
    private RuntimeException registryFailure;
    private volatile Decision decision;
    private final List<String> asked = Collections.synchronizedList(new ArrayList<>());
    private List<String> lastRan;

    @BeforeEach void setUp() {
        registered = Optional.of(delegated);
        decision = Decision.proceed();
    }

    private CurrentAuthority current() {
        return new CurrentAuthority(registry());
    }

    private GovernedAsyncDispatch.Registry registry() {
        return new GovernedAsyncDispatch.Registry() {
            @Override public Optional<InstanceAuthority> find(String id) {
                asked.add(id);
                if (registryFailure != null) throw registryFailure;
                return registered;
            }
            @Override public Decision recheck(Ulid id) { return decision; }
            @Override public void requireReauthorization(InstanceAuthority a, String reason) { }
            @Override public Optional<DelegationGrant> grant(Ulid id) { return Optional.empty(); }
        };
    }

    /** Runs the two-task fixture; afterTask runs when each task completes, after the guard has checked it. */
    private Object run(Ungoverned policy, Consumer<String> afterTask) throws Exception {
        var ranTasks = Collections.synchronizedList(new ArrayList<String>());
        var builder = WorkflowApplication.builder().disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new GovernedTaskGuard(current(), policy))
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onTaskStarted(TaskStartedEvent e) {
                        if (afterTask != null) afterTask.accept("started:" + e.taskContext().taskName());
                    }
                    @Override public void onTaskCompleted(TaskCompletedEvent e) {
                        ranTasks.add(e.taskContext().taskName());
                        if (afterTask != null) afterTask.accept(e.taskContext().taskName());
                    }
                });
        try (var app = builder.build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("identity/two-sets.yaml"))
                    .instance(Map.of());
            try {
                return instance.start().get(5, TimeUnit.SECONDS).asMap().orElseThrow();
            } catch (ExecutionException e) {
                return e.getCause();
            } finally {
                lastRan = List.copyOf(ranTasks);
            }
        }
    }

    private static Reason reason(Object outcome) {
        Throwable t = outcome instanceof Throwable x ? x : null;
        while (t != null && !(t instanceof AuthorityLostException)) t = t.getCause();
        assertNotNull(t, "expected an AuthorityLostException cause, got " + outcome);
        return ((AuthorityLostException) t).reason();
    }

    @Test void aCurrentAuthorityLetsEveryTaskRunAndTheGuardReadsTheEngineInstanceId() throws Exception {
        Object outcome = run(Ungoverned.REFUSE, null);
        assertEquals(Map.of("b", 2), outcome);
        assertEquals(List.of("first", "second"), lastRan);
        assertFalse(asked.isEmpty());
        assertTrue(asked.stream().allMatch(id -> id != null && !id.isBlank()), asked.toString());
    }

    @Test void aGrantRevokedBetweenTasksFailsTheNextTaskAndItNeverRuns() throws Exception {
        Object outcome = run(Ungoverned.REFUSE, task -> {
            if (task.equals("first")) decision = new Decision(Outcome.REAUTHORIZATION_REQUIRED, "grant.revoked");
        });
        assertEquals(Reason.REVOKED, reason(outcome));
        assertEquals(List.of("first"), lastRan, "the second task never completed");
    }

    @Test void aGrantRevokedDuringTheLastTaskWithholdsTheInstanceOutput() throws Exception {
        Object outcome = run(Ungoverned.REFUSE, task -> {
            // Flipped after the guard passed the start of the task and before its completion check: during the work.
            if (task.equals("started:second")) decision = new Decision(Outcome.REAUTHORIZATION_REQUIRED, "grant.expired");
        });
        assertEquals(Reason.REVOKED, reason(outcome), "the completion of the last task is checked too");
    }

    @Test void anInstanceWithoutAuthorityIsRefusedWhenEveryInstanceMustBeDelegated() throws Exception {
        registered = Optional.empty();
        assertEquals(Reason.NOT_VERIFIED, reason(run(Ungoverned.REFUSE, null)));
        assertTrue(lastRan.isEmpty());
    }

    @Test void anInstanceWithoutAuthorityRunsWhenUngovernedIsAllowed() throws Exception {
        registered = Optional.empty();
        assertEquals(Map.of("b", 2), run(Ungoverned.ALLOW, null));
        assertEquals(List.of("first", "second"), lastRan);
    }

    @Test void anUnreadableRegistryRefusesEvenWhenUngovernedIsAllowed() throws Exception {
        registryFailure = new IllegalStateException("db down");
        assertEquals(Reason.UNAVAILABLE, reason(run(Ungoverned.ALLOW, null)));
        assertTrue(lastRan.isEmpty());
    }

    @Test void anUndecidableCheckRefuses() throws Exception {
        decision = new Decision(Outcome.UNAVAILABLE, "authority.unavailable");
        assertEquals(Reason.UNAVAILABLE, reason(run(Ungoverned.ALLOW, null)));
    }

    @Test void theGuardNeedsADispatchAndAPolicy() {
        assertThrows(NullPointerException.class, () -> new GovernedTaskGuard(null, Ungoverned.ALLOW));
        assertThrows(NullPointerException.class, () -> new GovernedTaskGuard(current(), null));
    }
}
