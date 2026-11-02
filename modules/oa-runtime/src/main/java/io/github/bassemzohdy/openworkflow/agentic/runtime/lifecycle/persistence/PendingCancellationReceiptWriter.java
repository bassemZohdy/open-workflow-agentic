package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.serverlessworkflow.impl.WorkflowContextData;
import io.serverlessworkflow.impl.WorkflowDefinitionData;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceOperations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * Explicit, non-default writer for a guarded pending cancellation. The actual JPA delete and receipt insert share the
 * inherited writer's REQUIRES_NEW transaction. Not installed in the application reader or a production authority path.
 */
@ApplicationScoped
@Typed(PendingCancellationReceiptWriter.class)
public class PendingCancellationReceiptWriter extends JpaInstanceWriter {
    @Inject CancellationAwareJpaOperations guarded;

    @Override @Transactional(Transactional.TxType.REQUIRES_NEW)
    protected void doTransaction(Consumer<PersistenceInstanceOperations> work, WorkflowDefinitionData definition) {
        var operations = (PersistenceInstanceOperations) Proxy.newProxyInstance(
                PersistenceInstanceOperations.class.getClassLoader(), new Class<?>[]{PersistenceInstanceOperations.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("removeProcessInstance")) {
                        guarded.removePendingWithReceipt((WorkflowContextData) args[0]);
                        return null;
                    }
                    throw new IllegalStateException("pending cancellation writer only permits checkpoint removal");
                });
        work.accept(operations);
    }
}
