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
        Broker(int count) { registrations = new CountDownLatch(count); }
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder, Consumer<CloudEvent> callback,
                WorkflowContext workflow, TaskContext task) {
            var result = super.register(builder, callback, workflow, task); registrations.countDown(); return result;
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
}
