package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Runs the actual pinned SDK event/listen/lifecycle pipeline, not a reimplementation of its exception handler. */
class CheckpointLifecycleTest {
    static final class Broker extends InMemoryEvents {
        final CompletableFuture<Void> registered = new CompletableFuture<>();
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
            var result = super.register(builder, callback, workflow, task);
            registered.complete(null);
            return result;
        }
    }

    static PersistenceInstanceWriter failedCheckpointWriter() {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (method.getName().equals("taskCompleted")) {
                        return CompletableFuture.failedFuture(new IllegalStateException("test checkpoint unavailable"));
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    @Test
    void actualSdkSwallowsWriterFailureButGuardHoldsDeliveredWaitBeforeNextTask() throws Exception {
        for (boolean guarded : new boolean[]{false, true}) {
            try (var gate = new PersistenceCommitGate(Duration.ofSeconds(5), Clock.systemUTC()); var broker = new Broker()) {
                var nextStarted = new CompletableFuture<Void>(); var acknowledged = new AtomicInteger();
                PersistenceInstanceWriter writer = failedCheckpointWriter();
                if (guarded) writer = new CheckpointingWriter(writer, gate, (op, identity) -> {
                    if (op == PersistenceCommitGate.Operation.TASK_COMPLETED) acknowledged.incrementAndGet();
                });
                try (var application = WorkflowApplication.builder().withEventConsumer(broker)
                        .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                        .withListener(new WorkflowPersistenceListener(writer))
                        .withListener(new WorkflowExecutionListener() {
                            @Override public void onTaskStarted(TaskStartedEvent event) {
                                if (event.taskContext().taskName().equals("performEffect")) nextStarted.complete(null);
                            }
                        }).build()) {
                    var workflow = WorkflowReader.readWorkflowFromClasspath("lifecycle/checkpoint-gate.yaml");
                    var instance = application.workflowDefinition(workflow).instance(Map.of());
                    var completed = instance.start();
                    broker.registered.get(5, TimeUnit.SECONDS);
                    broker.publish(CloudEventBuilder.v1().withId("one").withSource(URI.create("urn:test:checkpoint"))
                            .withType("org.openworkflow.agentic.checkpoint.test").withData("application/json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .build()).get(5, TimeUnit.SECONDS);
                    if (guarded) {
                        assertEquals(PersistenceCommitGate.Operation.TASK_COMPLETED,
                                gate.firstFailure().get(5, TimeUnit.SECONDS).operation());
                        assertThrows(TimeoutException.class, () -> nextStarted.get(200, TimeUnit.MILLISECONDS));
                        assertFalse(completed.isDone()); assertEquals(0, acknowledged.get());
                        assertFalse(gate.status().healthy());
                    } else {
                        // Negative control: proves the pinned SDK really advances after this same failure.
                        nextStarted.get(5, TimeUnit.SECONDS);
                        completed.get(5, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }
}
