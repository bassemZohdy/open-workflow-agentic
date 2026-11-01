package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.Reason;
import io.serverlessworkflow.impl.lifecycle.TaskCompletedEvent;
import io.serverlessworkflow.impl.lifecycle.TaskResumedEvent;
import io.serverlessworkflow.impl.lifecycle.TaskStartedEvent;
import io.serverlessworkflow.impl.lifecycle.WorkflowExecutionCompletableListener;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Engine-side enforcement of the {@link CurrentAuthority} check (the one {@link GovernedAsyncDispatch} makes) for every task of a live instance (WP-102).
 * It is a {@link WorkflowExecutionCompletableListener}: the Flow engine fails a task, and so the instance, when such a
 * listener <em>throws</em>. A failed future is ignored and a plain synchronous {@code WorkflowExecutionListener} that
 * throws is swallowed, so neither enforces anything. This guard throws {@link AuthorityLostException} directly.
 *
 * <p>The authority is shown current when a task starts, when it resumes after a wait, and when it completes, so a grant
 * revoked or expired during blocking work fails the task before its output flows on. Only identifiers are read from the
 * event (the engine instance id); no bearer, credential or handle is carried or stored here.
 *
 * <p>An instance with no authority row is not governed (as in the resume gate). {@link Ungoverned#REFUSE} refuses it,
 * for deployments where every instance must be delegated. A registry that cannot answer always refuses.
 */
public final class GovernedTaskGuard implements WorkflowExecutionCompletableListener {

    /** What to do with an instance that has no authority row or no delegation grant. */
    public enum Ungoverned { ALLOW, REFUSE }

    private final CurrentAuthority authority;
    private final Ungoverned ungoverned;

    public GovernedTaskGuard(CurrentAuthority authority, Ungoverned ungoverned) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.ungoverned = Objects.requireNonNull(ungoverned, "ungoverned");
    }

    @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
        check(event.workflowContext().instanceData().id());
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletableFuture<?> onTaskResumed(TaskResumedEvent event) {
        check(event.workflowContext().instanceData().id());
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletableFuture<?> onTaskCompleted(TaskCompletedEvent event) {
        check(event.workflowContext().instanceData().id());
        return CompletableFuture.completedFuture(null);
    }

    private void check(String engineInstanceId) {
        try {
            authority.require(engineInstanceId);
        } catch (AuthorityLostException e) {
            if (e.reason() == Reason.NOT_VERIFIED && ungoverned == Ungoverned.ALLOW
                    && engineInstanceId != null && !engineInstanceId.isBlank()) return;
            throw e;
        }
    }
}
