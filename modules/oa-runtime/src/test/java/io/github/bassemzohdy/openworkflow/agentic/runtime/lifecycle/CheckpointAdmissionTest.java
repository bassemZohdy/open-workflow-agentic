package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PersistenceCommitGate;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class CheckpointAdmissionTest {
    @Test
    void unhealthyCheckpointStoreRefusesNewWorkButPreservesIdempotentRetryAndConflict() {
        var owner = new Subject(SubjectKind.USER, "alice");
        var request = new StartRequest("tenant", owner, new DefinitionRef("agentic/orders", "1"), Map.of(), "existing");
        var row = InstanceRecord.starting(UlidGenerator.shared().next(), request, Instant.now());
        var store = (InstanceStore) Proxy.newProxyInstance(InstanceStore.class.getClassLoader(), new Class<?>[]{InstanceStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByKey")) {
                        return args[0].equals("tenant") && args[1].equals("existing") ? Optional.of(row) : Optional.empty();
                    }
                    throw new AssertionError("must not create or change registry state: " + method.getName());
                });
        var engine = (WorkflowEngine) Proxy.newProxyInstance(WorkflowEngine.class.getClassLoader(), new Class<?>[]{WorkflowEngine.class},
                (proxy, method, args) -> { throw new AssertionError("must not call the engine: " + method.getName()); });
        var service = new InstanceService(store, engine, LifecycleFaults.none());
        try (var gate = new PersistenceCommitGate(Duration.ofSeconds(5), Clock.systemUTC())) {
            service.checkpoints = gate;
            gate.commit(PersistenceCommitGate.Operation.TASK_COMPLETED,
                    () -> CompletableFuture.failedFuture(new IllegalStateException("storage unavailable")), () -> {});
            assertEquals(new StartResult(row, false), service.start(request));
            assertThrows(IdempotencyKeyReusedException.class, () -> service.start(new StartRequest("tenant", owner,
                    request.definition(), Map.of("changed", true), "existing")));
            assertThrows(StoreUnavailableException.class, () -> service.start(new StartRequest("tenant", owner,
                    request.definition(), Map.of(), "new")));
            assertThrows(StoreUnavailableException.class, () -> service.start(new StartRequest("other-tenant", owner,
                    request.definition(), Map.of(), "existing")));
        }
    }
}
