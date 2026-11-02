package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Actual pinned SDK behavior, in memory only: no durable delivery, lease, checkpoint or authorization is proved. */
class HumanWakeSdkBehaviorTest {
    private static final String DOCUMENT = """
        document: { dsl: '1.0.3', namespace: agentic-test, name: human-wake, version: '1.0.0' }
        do:
          - awaitDecision:
              listen:
                to:
                  one:
                    with: { type: io.openworkflow.human.decision.v1, source: 'urn:openworkflow:human-decision' }
              timeout: { after: PT30S }
        """;
    private static final Ulid TASK = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final HumanDecision DECISION = new HumanDecision(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"),
            "tenant", TASK, 0, new Subject(SubjectKind.USER, "alice"), HumanDecision.Outcome.APPROVE, "private answer");
    private static final class Broker extends InMemoryEvents {
        final CountDownLatch registrations;
        final ConcurrentMap<String, TypeEventRegistration> byInstance = new ConcurrentHashMap<>();
        Broker(int count) { registrations = new CountDownLatch(count); }
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder, Consumer<CloudEvent> callback,
                WorkflowContext workflow, TaskContext task) {
            var result = super.register(builder, callback, workflow, task);
            byInstance.put(workflow.instanceData().id(), result); registrations.countDown(); return result;
        }
        /** Test-only, one-process targeted dispatch. It is not an ownership lease or a durable checkpoint. */
        boolean deliverTargeted(HumanDecision decision, InboxWait wait, InboxMessage message) {
            var registration = byInstance.get(wait.engineInstanceId());
            if (registration == null || registration.task().isCompleted()
                    || !registration.task().position().jsonPointer().equals(wait.taskPosition())
                    || registration.task().iteration() != wait.iteration()
                    || !registration.correlationPredicates().isEmpty()
                    || !HumanDecisionWake.matches(decision, wait, message)) return false;
            var event = StoredCloudEventDecoder.decode(message);
            if (!event.getType().equals(registration.type())
                    || !registration.predicate().test(event, registration.workflow(), registration.task())) return false;
            if (!byInstance.remove(wait.engineInstanceId(), registration)) return false;
            registration.consumer().accept(event);
            return true;
        }
    }
    private static InboxWait waitFor(WorkflowInstance instance, String position) {
        return new InboxWait("tenant", Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"), "app", instance.id(),
                "agentic-test", "human-wake", "1.0.0", position, 1, HumanDecisionWake.route(TASK),
                HumanDecisionWake.TYPE, "0".repeat(64), Duration.ofSeconds(30));
    }
    @Test void actualWorkflowReceivesTheReferenceOnlyCloudEvent() throws Exception {
        var broker = new Broker(1);
        try (var app = WorkflowApplication.builder().withEventConsumer(broker).disableLifeCycleCEPublishing()
                .disableStatusChangePublishing().build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML)).instance(Map.of());
            var completed = instance.start(); assertTrue(broker.registrations.await(5, TimeUnit.SECONDS));
            var wait = waitFor(instance, "do/0/awaitDecision");
            broker.publish(StoredCloudEventDecoder.decode(HumanDecisionWake.message(DECISION, wait))).get(5, TimeUnit.SECONDS);
            completed.get(5, TimeUnit.SECONDS);
            var results = instance.output().asCollection();
            assertEquals(1, results.size());
            var output = results.iterator().next().asMap().orElseThrow();
            assertEquals(DECISION.id().value(), output.get("decisionId").toString());
            assertEquals(wait.digest(), output.get("waitDigest").toString());
            assertFalse(output.toString().contains("private answer"));
            assertFalse(output.toString().contains("APPROVE"));
        }
    }
    @Test void directSdkBroadcastCanReachAnotherOccurrenceWithoutTheAdditionalFence() throws Exception {
        var broker = new Broker(2);
        try (var app = WorkflowApplication.builder().withEventConsumer(broker).disableLifeCycleCEPublishing()
                .disableStatusChangePublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML));
            var first = definition.instance(Map.of()); var second = definition.instance(Map.of());
            var firstDone = first.start(); var secondDone = second.start();
            assertTrue(broker.registrations.await(5, TimeUnit.SECONDS));
            var firstWait = waitFor(first, "do/0/awaitDecision"); var secondWait = waitFor(second, "do/0/awaitDecision");
            var message = HumanDecisionWake.message(DECISION, firstWait);
            assertFalse(HumanDecisionWake.matches(DECISION, secondWait, message));
            broker.publish(StoredCloudEventDecoder.decode(message)).get(5, TimeUnit.SECONDS);
            firstDone.get(5, TimeUnit.SECONDS); secondDone.get(5, TimeUnit.SECONDS);
            var result = second.output().asCollection();
            assertEquals(1, result.size());
            assertEquals(firstWait.digest(), result.iterator().next().asMap().orElseThrow().get("waitDigest").toString(),
                    "negative control: the bare SDK filter broadcasts the wrong occurrence too");
        }
    }

    @Test void targetedInMemoryCallbackReachesOnlyTheSelectedSdkOccurrence() throws Exception {
        var broker = new Broker(2);
        try (var app = WorkflowApplication.builder().withEventConsumer(broker).disableLifeCycleCEPublishing()
                .disableStatusChangePublishing().build()) {
            var definition = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML));
            var first = definition.instance(Map.of()); var second = definition.instance(Map.of());
            var firstDone = first.start(); var secondDone = second.start();
            assertTrue(broker.registrations.await(5, TimeUnit.SECONDS));
            var registration = broker.byInstance.get(first.id());
            var identity = registration.workflow().definition().id();
            var wait = new InboxWait("tenant", Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"),
                    registration.workflow().definition().application().id(), first.id(), identity.namespace(), identity.name(),
                    identity.version(), registration.task().position().jsonPointer(), registration.task().iteration(),
                    HumanDecisionWake.route(TASK), HumanDecisionWake.TYPE, "0".repeat(64), Duration.ofSeconds(30));
            var message = HumanDecisionWake.message(DECISION, wait);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> delivery = () -> { start.await(); return broker.deliverTargeted(DECISION, wait, message); };
                var left = pool.submit(delivery); var right = pool.submit(delivery); start.countDown();
                assertNotEquals(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS),
                        "concurrent attempts use the captured callback at most once");
            } finally { pool.shutdownNow(); }
            firstDone.get(5, TimeUnit.SECONDS);
            assertFalse(secondDone.isDone(), "no broadcast reaches the other waiting instance");
            assertFalse(broker.deliverTargeted(DECISION, wait, message), "a captured callback is used at most once in this process");
        }
    }
    @Test void capturedSdkRegistrationsAreTargetedRevocableAndNotInTheDelegateBroadcast() throws Exception {
        var delegate=new InMemoryEvents();var captures=new HumanWakeRegistrations(delegate,2);
        try(var app=WorkflowApplication.builder().withEventConsumer(captures).disableLifeCycleCEPublishing()
                .disableStatusChangePublishing().build()) {
            var definition=app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT,WorkflowFormat.YAML));
            var first=definition.instance(Map.of());var second=definition.instance(Map.of());
            var firstDone=first.start();var secondDone=second.start();
            var firstWait=new InboxWait("tenant",Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"),app.id(),first.id(),
                    "agentic-test","human-wake","1.0.0","do/0/awaitDecision",1,HumanDecisionWake.route(TASK),HumanDecisionWake.TYPE,"0".repeat(64),Duration.ofSeconds(30));
            var secondWait=new InboxWait("tenant",firstWait.instanceId(),app.id(),second.id(),firstWait.namespace(),firstWait.definition(),firstWait.version(),
                    firstWait.taskPosition(),1,firstWait.route(),firstWait.eventType(),firstWait.filterDigest(),firstWait.maxWait());
            var firstHandle=new java.util.concurrent.atomic.AtomicReference<HumanWakeRegistrations.Handle>();
            var secondHandle=new java.util.concurrent.atomic.AtomicReference<HumanWakeRegistrations.Handle>();
            assertTimeoutPreemptively(Duration.ofSeconds(5),()->{
                while(firstHandle.get()==null||secondHandle.get()==null){
                    captures.find(firstWait).ifPresent(firstHandle::set);captures.find(secondWait).ifPresent(secondHandle::set);
                    if(firstHandle.get()==null||secondHandle.get()==null)Thread.sleep(5);
                }
            });
            var event=StoredCloudEventDecoder.decode(HumanDecisionWake.message(DECISION,firstWait));
            delegate.publish(event).get(5,TimeUnit.SECONDS);
            assertFalse(firstDone.isDone());assertFalse(secondDone.isDone(),"human type was not registered with the broadcast delegate");
            try(var bounded=new HumanWakeRegistrations(new InMemoryEvents(),1)) {
                var firstRegistration=firstHandle.get().registration();var secondRegistration=secondHandle.get().registration();
                var builder=new TypeEventRegistrationBuilder(firstRegistration.type(),firstRegistration.predicate(),firstRegistration.correlationPredicates());
                bounded.register(builder,firstRegistration.consumer(),firstRegistration.workflow(),firstRegistration.task());
                assertThrows(IllegalStateException.class,()->bounded.register(builder,secondRegistration.consumer(),secondRegistration.workflow(),secondRegistration.task()));
                assertTrue(bounded.find(firstWait).orElseThrow().isCurrent());assertTrue(bounded.find(secondWait).isEmpty());
            }
            firstHandle.get().registration().consumer().accept(event);firstDone.get(5,TimeUnit.SECONDS);
            assertFalse(firstHandle.get().isCurrent());assertTrue(captures.find(firstWait).isEmpty());
            assertTrue(secondHandle.get().isCurrent());
            var old=secondHandle.get().registration();assertTrue(secondHandle.get().begin());
            assertFalse(secondHandle.get().begin(),"one captured registration cannot start two callbacks");
            captures.unregister(old);assertFalse(secondHandle.get().isCurrent());
            var builder=new TypeEventRegistrationBuilder(old.type(),old.predicate(),old.correlationPredicates());
            assertThrows(IllegalStateException.class,()->captures.register(builder,old.consumer(),old.workflow(),old.task()),
                    "an unregistered in-flight callback prevents a replacement until it returns");
            secondHandle.get().end();
            var replacement=captures.register(builder,
                    old.consumer(),old.workflow(),old.task());
            var fresh=captures.find(secondWait).orElseThrow();captures.unregister(old);
            assertTrue(fresh.isCurrent(),"late unregistration cannot remove its replacement");
            replacement.consumer().accept(StoredCloudEventDecoder.decode(HumanDecisionWake.message(DECISION,secondWait)));
            secondDone.get(5,TimeUnit.SECONDS);assertFalse(fresh.isCurrent());captures.unregister(replacement);
        }
    }
}
