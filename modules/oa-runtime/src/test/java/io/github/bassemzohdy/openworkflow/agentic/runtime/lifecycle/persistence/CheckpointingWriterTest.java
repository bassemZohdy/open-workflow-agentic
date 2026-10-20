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
    private record Contexts(WorkflowContextData workflow,TaskContextData task) { }
    private static Contexts contexts(AtomicInteger iteration,java.util.function.Supplier<WorkflowStatus> status) {
        var definition=fixture(WorkflowDefinitionData.class,Map.of("id",new WorkflowDefinitionId("agentic","orders","1")));
        var instance=fixture(WorkflowInstanceData.class,Map.of("id","engine-1","status",status));
        var workflow=fixture(WorkflowContextData.class,Map.of("definition",definition,"instanceData",instance));
        var position=fixture(WorkflowPosition.class,Map.of("jsonPointer","do/0/listen"));
        var task=fixture(TaskContextData.class,Map.of("position",position,"iteration",(java.util.function.Supplier<Integer>)iteration::get));
        return new Contexts(workflow,task);
    }
    private static PersistenceInstanceWriter writing(CompletableFuture<Void> actual,AtomicInteger calls) {
        return (PersistenceInstanceWriter)Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class},(p,m,args)-> {
                    if(m.getName().equals("close")) return null;
                    calls.incrementAndGet();return actual;
                });
    }
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
        var instance = fixture(WorkflowInstanceData.class, Map.of("id", "engine-1", "status", WorkflowStatus.RUNNING));
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

    @Test void capturesBeforeWriterSubmissionThenWaitsForActualCommitAndAsynchronousAckBeforeReleasingProgress() {
        var iteration=new AtomicInteger(7);var c=contexts(iteration,()->WorkflowStatus.RUNNING);
        var actual=new CompletableFuture<Void>();var ack=new CompletableFuture<Void>();
        var writes=new AtomicInteger();var finished=new AtomicInteger();var observed=new AtomicInteger();
        try(var gate=new PersistenceCommitGate(Duration.ofSeconds(2),Clock.systemUTC())) {
            var writer=new CheckpointingWriter(writing(actual,writes),gate,(op,id)-> {
                assertEquals(7,id.iteration());observed.incrementAndGet();
            },(op,w,t)-> {
                assertEquals(0,writes.get(),"snapshot must precede handing the mutable task to the writer");
                int captured=t.iteration();
                return ()->{ assertEquals(7,captured);finished.incrementAndGet();return ack; };
            });
            var stage=writer.taskCompleted(c.workflow,c.task);iteration.set(8);
            assertEquals(1,writes.get());assertEquals(0,finished.get());assertFalse(stage.isDone());
            actual.complete(null);
            assertEquals(1,finished.get());assertEquals(0,observed.get());assertFalse(stage.isDone());
            assertTrue(gate.status().healthy());
            ack.complete(null);
            assertTrue(stage.isDone());assertEquals(1,observed.get());assertTrue(gate.status().healthy());
        }
    }

    @Test void failedOrTimedOutAckNeverReleasesTheAlreadyCommittedCheckpointEvenOnLateSuccess() throws Exception {
        for(boolean timeout:new boolean[]{false,true}) {
            var c=contexts(new AtomicInteger(1),()->WorkflowStatus.RUNNING);
            var ack=new CompletableFuture<Void>();var observed=new AtomicInteger();var writes=new AtomicInteger();
            try(var gate=new PersistenceCommitGate(Duration.ofMillis(80),Clock.systemUTC())) {
                var writer=new CheckpointingWriter(writing(CompletableFuture.completedFuture(null),writes),gate,
                        (op,id)->observed.incrementAndGet(),(op,w,t)->()->ack);
                var stage=writer.taskCompleted(c.workflow,c.task);
                assertEquals(1,writes.get());assertFalse(stage.isDone());
                if(!timeout) ack.completeExceptionally(new IllegalStateException("ACK unavailable"));
                assertTimeoutPreemptively(Duration.ofSeconds(2),()-> { while(gate.status().healthy()) Thread.sleep(5); });
                if(timeout) assertTrue(ack.complete(null));
                assertFalse(stage.isDone());assertEquals(0,observed.get());assertFalse(gate.status().healthy());
            }
        }
    }

    @Test void snapshotOrActualWriteFailureNeverRunsPostCommitAckOrObserver() {
        for(boolean snapshotFailure:new boolean[]{false,true}) {
            var c=contexts(new AtomicInteger(1),()->WorkflowStatus.RUNNING);
            var writes=new AtomicInteger();var after=new AtomicInteger();var observed=new AtomicInteger();
            try(var gate=new PersistenceCommitGate(Duration.ofSeconds(2),Clock.systemUTC())) {
                var writer=new CheckpointingWriter(writing(CompletableFuture.failedFuture(new IllegalStateException("write failed")),writes),
                        gate,(op,id)->observed.incrementAndGet(),(op,w,t)-> {
                            if(snapshotFailure) throw new IllegalStateException("bad marker snapshot");
                            return ()->{after.incrementAndGet();return CompletableFuture.completedFuture(null);};
                        });
                assertFalse(writer.taskCompleted(c.workflow,c.task).isDone());
                assertFalse(gate.status().healthy());assertEquals(snapshotFailure?0:1,writes.get());
                assertEquals(0,after.get());assertEquals(0,observed.get());
            }
        }
    }

    @Test void cancellationDuringAckHoldsTheStageWithoutPoisoningIndependentCleanup() {
        var status=new java.util.concurrent.atomic.AtomicReference<>(WorkflowStatus.RUNNING);
        var c=contexts(new AtomicInteger(1),status::get);var ack=new CompletableFuture<Void>();
        var observed=new ArrayList<PersistenceCommitGate.Operation>();
        try(var gate=new PersistenceCommitGate(Duration.ofSeconds(2),Clock.systemUTC())) {
            var writer=new CheckpointingWriter(writing(CompletableFuture.completedFuture(null),new AtomicInteger()),gate,
                    (op,id)->observed.add(op),(op,w,t)->()->op==PersistenceCommitGate.Operation.TASK_COMPLETED
                            ?ack:CompletableFuture.completedFuture(null));
            var stage=writer.taskCompleted(c.workflow,c.task);status.set(WorkflowStatus.CANCELLED);
            ack.completeExceptionally(new IllegalStateException("cancelled while ACK unavailable"));
            assertFalse(stage.isDone());assertTrue(gate.status().healthy());assertTrue(observed.isEmpty());
            assertTrue(writer.aborted(c.workflow).isDone());
            assertEquals(List.of(PersistenceCommitGate.Operation.ABORTED),observed);assertTrue(gate.status().healthy());
        }
    }
}
