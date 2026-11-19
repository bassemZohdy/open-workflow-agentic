package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;

import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.AtomicInMemoryEvents;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.api.WorkflowReader;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real injected application uses one broker for listening and publishing; no manually constructed application. */
@QuarkusTest
@Timeout(30)
class AtomicEventBrokerTest {
    @Inject WorkflowApplication application;

    @Test
    void injectedApplicationPublishesToItsAtomicLocalConsumerAcrossSuccessiveWaits() throws Exception {
        var broker = assertInstanceOf(AtomicInMemoryEvents.class, application.eventConsumer());
        assertEquals(1, application.eventPublishers().stream().filter(p -> p == broker).count(),
                "the selected consumer must be the same local publisher, without a separate fallback broker");
        String name = "atomic-events-" + UUID.randomUUID().toString().replace("-", "");
        String type = "org.openworkflow.agentic.test." + name;
        var workflow = WorkflowReader.readWorkflowFromString("""
                document:
                  dsl: '1.0.0'
                  namespace: event-race-test
                  name: %s
                  version: '1.0.0'
                do:
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: '%s' }
                      timeout: { after: PT10S }
                  - finish:
                      set: { status: completed }
                """.formatted(name, type), io.serverlessworkflow.api.WorkflowFormat.YAML);
        var definition = application.workflowDefinition(workflow);
        for (int iteration = 0; iteration < 3; iteration++) {
            var instance = definition.instance(Map.of());
            var done = instance.start();
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (instance.status() != WorkflowStatus.WAITING) {
                        assertFalse(done.isDone(), "workflow must wait before the event is published");
                        Thread.sleep(1);
                    }
                });
                // WAITING is emitted before registration; repeated offers tolerate that documented boundary.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!done.isDone() && System.nanoTime() < deadline) {
                    broker.publish(CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                            .withSource(URI.create("urn:test:atomic-broker")).withType(type).build())
                            .get(5, TimeUnit.SECONDS);
                    if (!done.isDone()) Thread.sleep(10);
                }
                done.get(5, TimeUnit.SECONDS);
                assertEquals(WorkflowStatus.COMPLETED, instance.status());
            } finally {
                if (!done.isDone()) instance.cancel();
            }
        }
    }
}
