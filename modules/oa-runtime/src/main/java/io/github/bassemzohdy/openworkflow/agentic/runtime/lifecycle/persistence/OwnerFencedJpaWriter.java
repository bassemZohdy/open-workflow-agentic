package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowOwnerContextBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowOwnerJpaFence;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.serverlessworkflow.impl.WorkflowContextData;
import io.serverlessworkflow.impl.WorkflowDefinitionData;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceOperations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Explicit candidate JPA writer: the exact context's bound generation fences actual persistence in the SAME JTA
 * transaction. It is not selected by the application. A failed listener may be suppressed by the SDK, so this is
 * not an execution or side-effect fence. A trusted owner lifecycle must bind and maintain each context first.
 */
@ApplicationScoped
@Typed(OwnerFencedJpaWriter.class)
public class OwnerFencedJpaWriter extends JpaInstanceWriter {
    @Inject WorkflowOwnerContextBindings bindings;
    @Inject WorkflowOwnerJpaFence fence;

    private Consumer<PersistenceInstanceOperations> guarded(Consumer<PersistenceInstanceOperations> operation,
                                                             WorkflowContextData context) {
        var claim = bindings.require(context); // Capture before dispatch; later ownership changes cannot replace it.
        return operations -> {
            fence.requireCurrent(context, claim);
            operation.accept(operations);
            fence.requireCurrent(context, claim);
        };
    }

    @Override protected CompletableFuture<Void> doTransaction(Consumer<PersistenceInstanceOperations> operation,
                                                              WorkflowContextData context) {
        try { return super.doTransaction(guarded(operation, context), context); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    @Override protected CompletableFuture<Void> doStartInstance(Consumer<PersistenceInstanceOperations> operation,
                                                                WorkflowContextData context) {
        try { return super.doStartInstance(guarded(operation, context), context); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    @Override protected CompletableFuture<Void> doCompleteInstance(Consumer<PersistenceInstanceOperations> operation,
                                                                   WorkflowContextData context) {
        try { return super.doCompleteInstance(guarded(operation, context), context); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    @Override @Transactional(Transactional.TxType.REQUIRES_NEW)
    protected void doTransaction(Consumer<PersistenceInstanceOperations> operation, WorkflowDefinitionData definition) {
        super.doTransaction(operation, definition);
    }
}
