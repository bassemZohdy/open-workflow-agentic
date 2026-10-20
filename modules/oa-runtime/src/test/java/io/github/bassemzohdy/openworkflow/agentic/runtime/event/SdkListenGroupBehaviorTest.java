package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.lifecycle.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Actual pinned full-workflow observations, including explicit negative controls. Not durable delivery or T13 evidence. */
class SdkListenGroupBehaviorTest {
    static final String A="org.openworkflow.agentic.group.a",B="org.openworkflow.agentic.group.b",STOP="org.openworkflow.agentic.group.stop";
    static final class Broker extends InMemoryEvents {
        final CountDownLatch registrations;
        Broker(int expected) {registrations=new CountDownLatch(expected);}
        public TypeEventRegistration register(TypeEventRegistrationBuilder b,Consumer<CloudEvent> callback,WorkflowContext w,TaskContext t) {
            var registered=super.register(b,callback,w,t);registrations.countDown();return registered;
        }
        void send(String type,String id,int sequence) throws Exception {
            publish(CloudEventBuilder.v1().withId(id).withSource(URI.create("urn:test:listen-groups")).withType(type)
                    .withData("application/json",("{\"id\":\""+id+"\",\"seq\":"+sequence+"}").getBytes(StandardCharsets.UTF_8)).build())
                    .get(5,TimeUnit.SECONDS);
        }
    }
    static List<String> ids(WorkflowModel output) {
        return output.asCollection().stream().map(v->v.asMap().orElseThrow().get("id").toString()).toList();
    }
    static final class Running implements AutoCloseable {
        final Broker broker=new Broker(2);
        final WorkflowApplication app;
        final WorkflowInstance instance;
        final CompletableFuture<WorkflowModel> completed;
        final CompletableFuture<List<String>> atCompletion=new CompletableFuture<>();
        Running(String fixture,WorkflowExecutionCompletableListener listener) throws Exception {
            app=WorkflowApplication.builder().withEventConsumer(broker).disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowExecutionListener() {
                        public void onWorkflowCompleted(WorkflowCompletedEvent e) {atCompletion.complete(ids(e.output()));}
                    }).withListener(listener).build();
            instance=app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("event/"+fixture+".yaml")).instance(Map.of());
            completed=instance.start();assertTrue(broker.registrations.await(5,TimeUnit.SECONDS));
        }
        Running(String fixture) throws Exception {this(fixture,new WorkflowExecutionCompletableListener(){});}
        public void close() {app.close();}
    }
    @Test void allDistinctFiltersKeepArrivalOrderRatherThanDeclarationOrder() throws Exception {
        try(var run=new Running("listen-all-order")) {
            run.broker.send(B,"b-first",1);assertFalse(run.completed.isDone());
            run.broker.send(A,"a-second",2);run.completed.get(5,TimeUnit.SECONDS);
            assertEquals(List.of("b-first","a-second"),run.atCompletion.get(5,TimeUnit.SECONDS));
            assertEquals(List.of("b-first","a-second"),ids(run.instance.output()));
        }
    }
    @Test void oneReceiptSatisfiesBothOverlappingAllFiltersInTheActualWorkflow() throws Exception {
        try(var run=new Running("listen-all-overlap")) {
            run.broker.send(A,"one",1);run.completed.get(5,TimeUnit.SECONDS);
            assertEquals(List.of("one","one"),ids(run.instance.output()),"registration membership is distinct from receipt identity");
        }
    }
    @Test void capturedAnyLoserMutatesOutputAfterTheActualWorkflowAlreadyCompleted() throws Exception {
        try(var run=new Running("listen-any-overlap")) {
            run.broker.send(A,"one",1);run.completed.get(5,TimeUnit.SECONDS);
            assertEquals(List.of("one"),run.atCompletion.get(5,TimeUnit.SECONDS));
            assertEquals(List.of("one","one"),ids(run.instance.output()),"NEGATIVE CONTROL: the bridge must fence captured losers before SDK callback");
            run.broker.send(A,"later",2);assertEquals(List.of("one","one"),ids(run.instance.output()));
        }
    }
    @Test void defaultForeachOverlapsAndStopCanCompleteBeforeAnEarlierChildFinishes() throws Exception {
        var firstStarted=new CompletableFuture<Void>();var secondStarted=new CompletableFuture<Void>();
        var releaseFirst=new CompletableFuture<Void>();
        var listener=new WorkflowExecutionCompletableListener() {
            public CompletableFuture<?> onTaskStarted(TaskStartedEvent e) {
                if(!e.taskContext().taskName().equals("processItem")) return CompletableFuture.completedFuture(null);
                var sequence=((Number)e.taskContext().input().asMap().orElseThrow().get("seq")).intValue();
                if(sequence==1) {firstStarted.complete(null);return releaseFirst;}
                secondStarted.complete(null);return CompletableFuture.completedFuture(null);
            }
        };
        try(var run=new Running("listen-foreach-until",listener)) {
            run.broker.send(A,"first",1);firstStarted.get(5,TimeUnit.SECONDS);
            run.broker.send(A,"second",2);secondStarted.get(5,TimeUnit.SECONDS);
            assertFalse(releaseFirst.isDone(),"NEGATIVE CONTROL: the second child starts while the first is blocked");
            run.broker.send(STOP,"stop",3);run.completed.get(5,TimeUnit.SECONDS);
            assertEquals(List.of("second"),run.atCompletion.get(5,TimeUnit.SECONDS));
            releaseFirst.complete(null);
            assertEquals(List.of("second","first"),ids(run.instance.output()),"NEGATIVE CONTROL: late child mutates already-completed output");
            assertFalse(ids(run.instance.output()).contains("stop"),"until events are not result data");
        } finally {releaseFirst.complete(null);}
    }
}
