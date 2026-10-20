package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Actual SDK/JPA/PostgreSQL boundary: task-start success is not a durable business-occurrence reservation. */
@QuarkusTest
class PersistenceOccurrenceBoundaryTest {
    static final String META="gpt:test:occurrence-reference", VALUE="immutable-coordinate-reference";
    static final String TYPE="org.openworkflow.agentic.occurrence-boundary";
    static final String HEADER="""
            document:
              dsl: '1.0.3'
              namespace: agentic-test
              name: occurrence-boundary
              version: '1.0.0'
              title: Observe actual task metadata persistence
              summary: No credentials or target calls; compare start notification with completion storage.
            """;
    static final String SET_TASK="""
              - rememberOccurrence:
                  set: { remembered: true }
            """;
    static final String LISTEN_TASK="""
              - awaitSignal:
                  listen:
                    to:
                      one:
                        with: { type: org.openworkflow.agentic.occurrence-boundary }
                  timeout: { after: PT30S }
            """;
    @Inject JpaInstanceWriter writer;
    @Inject JpaInstanceOperations operations;
    static class Broker extends InMemoryEvents {
        final CompletableFuture<WorkflowContext> listening=new CompletableFuture<>();
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder b,Consumer<CloudEvent> callback,
                                                       WorkflowContext workflow,TaskContext task) {
            var registration=super.register(b,callback,workflow,task);listening.complete(workflow);return registration;
        }
    }
    PersistenceInstanceWriter borrowedWriter() {
        // Test-owned applications may not close the runtime's injected shared persistence executor.
        return (PersistenceInstanceWriter)Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class},(p,m,a)->{
                    if(m.getName().equals("close")) return null;
                    try {return m.invoke(writer,a);}catch(InvocationTargetException e){throw e.getCause();}
                });
    }
    @Test void taskStartSuccessAndInMemoryMetadataDoNotPersistAnOccurrenceBeforeDispatch() throws Exception {observe(false);}
    @Test void actualCompletionCommitRetainsTheSameMetadataForASeparateStorageRead() throws Exception {observe(true);}

    void observe(boolean completedBeforeWait) throws Exception {
        String stampTask=completedBeforeWait?"rememberOccurrence":"awaitSignal";
        var stampedPosition=new AtomicReference<String>();var successfulStarts=ConcurrentHashMap.<String>newKeySet();
        var broker=new Broker();
        try(var gate=new PersistenceCommitGate(Duration.ofSeconds(15),Clock.systemUTC())) {
            var guarded=new CheckpointingWriter(borrowedWriter(),gate,(op,checkpoint)->{
                if(op==PersistenceCommitGate.Operation.TASK_STARTED) successfulStarts.add(checkpoint.taskPosition());
            });
            try(var app=WorkflowApplication.builder().withId("occurrence-boundary-"+UUID.randomUUID())
                    .withEventConsumer(broker).disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(guarded))
                    .withListener(new WorkflowExecutionListener(){
                        public void onTaskStarted(TaskStartedEvent event) {
                            if(event.taskContext().taskName().equals(stampTask)) {
                                stampedPosition.set(event.taskContext().position().jsonPointer());
                                ((WorkflowContext)event.workflowContext()).instance().addMetadataIfAbsent(META,()->VALUE);
                            }
                        }
                    }).build()) {
                var definition=app.workflowDefinition(WorkflowReader.readWorkflowFromString(
                        HEADER+"do:\n"+(completedBeforeWait?SET_TASK:"")+LISTEN_TASK,WorkflowFormat.YAML));
                var instance=definition.instance(Map.of());var completed=instance.start();
                var context=broker.listening.get(10,TimeUnit.SECONDS);
                try {
                    assertTrue(successfulStarts.contains(stampedPosition.get()),"the actual writer future/gate reported start success");
                    assertEquals(VALUE,context.instanceData().findMetadata(META,String.class).orElseThrow());
                    var stored=QuarkusTransaction.requiringNew().call(()->operations.readWorkflowInfo(definition,instance.id()).orElseThrow());
                    assertFalse(stored.metadata().containsKey(META),"initial instance storage predates the task's marker");
                    if(completedBeforeWait) {
                        var task=assertInstanceOf(CompletedTaskInfo.class,stored.tasks().get(stampedPosition.get()));
                        assertEquals(VALUE,task.additionalObjects().get(META),"real completion transaction retained the marker");
                    } else {
                        assertFalse(stored.tasks().containsKey(stampedPosition.get()),
                                "start notification alone stored no task/cursor; own durable reservation is still required");
                    }
                    assertFalse(completed.isDone());assertTrue(gate.status().healthy());
                } finally {
                    // Finish this test-owned workflow normally, awaiting actual cleanup before closing borrowed services.
                    broker.publish(CloudEventBuilder.v1().withId(UUID.randomUUID().toString()).withSource(URI.create("urn:test:occurrence"))
                            .withType(TYPE).withData("application/json","{\"done\":true}".getBytes(StandardCharsets.UTF_8)).build()).get(10,TimeUnit.SECONDS);
                    completed.get(10,TimeUnit.SECONDS);
                }
                assertTrue(QuarkusTransaction.requiringNew().call(()->operations.readWorkflowInfo(definition,instance.id())).isEmpty());
            }
        }
    }
}
