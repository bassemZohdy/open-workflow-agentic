package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import static org.junit.jupiter.api.Assertions.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceWriter;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CheckpointingWriterTest {
    @SuppressWarnings("unchecked")
    static <T> T fixture(Class<T> type, Map<String, Object> fields) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (!fields.containsKey(method.getName())) throw new AssertionError("unexpected context access: " + method.getName());
            Object value = fields.get(method.getName());
            return value instanceof java.util.function.Supplier<?> supplier ? supplier.get() : value;
        });
    }

    @Test
    void allWriterOperationsObserveOnlyActualSuccessWithImmutableTaskOccurrenceIdentity() throws Exception {
        var definition = fixture(WorkflowDefinitionData.class, Map.of("id", new WorkflowDefinitionId("agentic", "orders", "1")));
        var instance = fixture(WorkflowInstanceData.class, Map.of("id", "engine-1"));
        var workflow = fixture(WorkflowContextData.class, Map.of("definition", definition, "instanceData", instance));
        AtomicInteger iteration = new AtomicInteger(7);
        var position = fixture(WorkflowPosition.class, Map.of("jsonPointer", "/do/0/listen"));
        var task = fixture(TaskContextData.class, Map.of("position", position, "iteration", (java.util.function.Supplier<Integer>) iteration::get));
        List<String> called = new ArrayList<>(); List<Object[]> arguments = new ArrayList<>();
        var pending = new CompletableFuture<?>[1];
        var delegate = (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    called.add(method.getName()); arguments.add(args);
                    return method.getName().equals("close") ? null : pending[0];
                });
        try (var gate = new PersistenceCommitGate(Duration.ofSeconds(5), Clock.systemUTC())) {
            List<CheckpointCommitObserver.Checkpoint> observed = new ArrayList<>();
            List<PersistenceCommitGate.Operation> operations = new ArrayList<>();
            var writer = new CheckpointingWriter(delegate, gate, (op, checkpoint) -> { operations.add(op); observed.add(checkpoint); });
            RuntimeException failure = new RuntimeException("workflow failure");
            for (var method : PersistenceInstanceWriter.class.getMethods()) {
                if (method.getName().equals("close")) continue;
                Object[] args = method.getParameterCount() == 1 ? new Object[]{workflow}
                        : new Object[]{workflow, method.getParameterTypes()[1] == Throwable.class ? failure : task};
                iteration.set(7); var actual = new CompletableFuture<Void>(); pending[0] = actual;
                int before = observed.size();
                var stage = (CompletableFuture<?>) method.invoke(writer, args);
                assertEquals(method.getName(), called.getLast());
                assertSame(workflow, arguments.getLast()[0]);
                if (args.length > 1) assertSame(args[1], arguments.getLast()[1]);
                iteration.set(8); // SDK context can change later; acknowledgement identity cannot.
                assertFalse(stage.isDone()); assertEquals(before, observed.size());
                actual.complete(null);
                assertTrue(stage.isDone()); assertEquals(before + 1, observed.size());
                var checkpoint = observed.getLast();
                assertEquals("engine-1", checkpoint.engineInstanceId());
                assertEquals("agentic", checkpoint.namespace()); assertEquals("orders", checkpoint.definition());
                assertEquals("1", checkpoint.version());
                boolean taskOperation = method.getName().startsWith("task");
                assertEquals(taskOperation ? "/do/0/listen" : null, checkpoint.taskPosition());
                assertEquals(taskOperation ? 7 : 0, checkpoint.iteration());
            }
            assertEquals(EnumSet.allOf(PersistenceCommitGate.Operation.class), EnumSet.copyOf(operations));
            assertEquals(9, operations.size());
            writer.close(); assertEquals("close", called.getLast());
        }
    }
}
