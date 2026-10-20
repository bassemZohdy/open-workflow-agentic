package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import java.lang.reflect.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deterministically orders an actual JPA task checkpoint after committed SDK cancellation/cleanup. */
@QuarkusTest
class LateCancelledCheckpointTest {
    @Inject JpaInstanceWriter writer;

    static class Broker extends InMemoryEvents {
        final CompletableFuture<Void> registered=new CompletableFuture<>();
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,Consumer<CloudEvent> callback,
                                                       WorkflowContext workflow,TaskContext task) {
            var result=super.register(builder,callback,workflow,task); registered.complete(null); return result;
        }
    }

    @Test void aLateTaskCheckpointAfterCancellationCannotPoisonOtherWorkflowStartsOrReleaseCancelledProgress() throws Exception {
        var entered=new CompletableFuture<Void>(); var release=new CompletableFuture<Void>();
        var lateFinished=new CompletableFuture<Void>(); var next=new CompletableFuture<Void>();
        var first=new AtomicBoolean(); var committedTasks=new AtomicInteger();
        PersistenceInstanceWriter delayed=(PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class},(proxy,method,args)-> {
                    if (method.getName().equals("close")) return null; // Borrow, never close the shared CDI executor.
                    if (method.getName().equals("taskCompleted") && first.compareAndSet(false,true)) {
                        entered.complete(null);
                        var actual=release.thenCompose(__->writer.taskCompleted((WorkflowContextData)args[0],(TaskContextData)args[1]));
                        actual.whenComplete((__,error)->lateFinished.complete(null)); return actual;
                    }
                    try { return method.invoke(writer,args); }
                    catch(InvocationTargetException error) { throw error.getCause(); }
                });
        try(var gate=new PersistenceCommitGate(Duration.ofSeconds(15),Clock.systemUTC()); var broker=new Broker()) {
            var guarded=new CheckpointingWriter(delayed,gate,(op,id)-> {
                if(op==PersistenceCommitGate.Operation.TASK_COMPLETED) committedTasks.incrementAndGet();
            });
            try(var app=WorkflowApplication.builder().withId("cancel-checkpoint-"+UUID.randomUUID()).withEventConsumer(broker)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(guarded))
                    .withListener(new WorkflowExecutionListener() {
                        @Override public void onTaskStarted(TaskStartedEvent event) {
                            if(event.taskContext().taskName().equals("performEffect")) next.complete(null);
                        }
                    }).build()) {
                var definition=app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("event/atomic-receipt.yaml"));
                var instance=definition.instance(Map.of()); instance.start();
                broker.registered.get(10,TimeUnit.SECONDS);
                broker.publish(CloudEventBuilder.v1().withId("late").withSource(URI.create("urn:test:late-checkpoint"))
                        .withType("org.openworkflow.agentic.atomic.test").build()).get(10,TimeUnit.SECONDS);
                entered.get(10,TimeUnit.SECONDS);
                assertTrue(instance.cancelFuture().get(10,TimeUnit.SECONDS)); // Actual ABORTED writer has committed.
                release.complete(null); lateFinished.get(10,TimeUnit.SECONDS);
                assertTimeoutPreemptively(Duration.ofSeconds(5),()-> {
                    while(gate.status().heldCalls()==0 && gate.status().failure().isEmpty() && committedTasks.get()==0) Thread.sleep(10);
                });
                assertTrue(gate.status().healthy(),()->"expected cancellation poisoned the gate: "+gate.status());
                assertEquals(0,committedTasks.get(),"a cancelled checkpoint is not successful consumption");
                assertTrue(gate.status().heldCalls()>0); assertFalse(next.isDone());
                // A separate workflow using the same actual writer/barrier must still checkpoint normally.
                var successor=app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("lifecycle/cancel-successor.yaml"));
                successor.instance(Map.of()).start().get(10,TimeUnit.SECONDS);
                assertTrue(gate.status().healthy());
            } finally { release.complete(null); }
        }
    }
}
