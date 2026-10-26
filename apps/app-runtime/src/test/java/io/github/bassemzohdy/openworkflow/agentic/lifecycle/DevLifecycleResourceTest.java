package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.events.EventPublisher;
import io.cloudevents.CloudEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.*;
import org.junit.jupiter.api.Test;

class DevLifecycleResourceTest {
    static final DefinitionRef DEFINITION = new DefinitionRef("agentic/lifecycle-probe", "0.1.0");
    static DevLifecycleResource resource() { var r = new DevLifecycleResource(); r.enabled = true; return r; }
    static WorkflowEngine engine(Optional<WorkflowEngine.ActiveInstance> view) {
        return new WorkflowEngine() {
            public boolean hasDefinition(DefinitionRef d) { throw new AssertionError("diagnosis cannot start work"); }
            public EngineLaunch prepare(DefinitionRef d, Map<String,Object> input) { throw new AssertionError("diagnosis cannot prepare work"); }
            public Optional<ActiveInstance> activeInstance(DefinitionRef d, String id) {
                assertEquals(DEFINITION, d); assertEquals("engine-y", id); return view;
            }
        };
    }
    @Test void disabledProbeReturns404WithoutLookingAtEngineOrRequest() {
        var r = new DevLifecycleResource();
        assertEquals(404, r.engine(null, null, null).getStatus());
        assertEquals(404, r.publish(null).getStatus());
    }
    @Test void snapshotReportsEveryActualEngineStatusWithoutStartingOrRepairing() {
        var r = resource();
        for (var status : EngineStatus.values()) {
            r.engine = engine(Optional.of(new WorkflowEngine.ActiveInstance("engine-y", status)));
            try (var response = r.engine(DEFINITION.definitionId(), DEFINITION.version(), "engine-y")) {
                assertEquals(200, response.getStatus());
                assertEquals(new DevLifecycleResource.EngineProbe(true, status.name()), response.getEntity());
            }
        }
    }
    @Test void absentIsExplicitlyUnknownRatherThanACompletedOutcome() {
        var r = resource(); r.engine = engine(Optional.empty());
        try (var response = r.engine(DEFINITION.definitionId(), DEFINITION.version(), "engine-y")) {
            assertEquals(200, response.getStatus());
            assertEquals(new DevLifecycleResource.EngineProbe(false, "ABSENT"), response.getEntity());
        }
    }
    @Test void malformedRequestsRefuseBeforeEngineAccessAndUnavailableDoesNotExposeExceptionText() {
        var r = resource();
        assertEquals(400, r.engine(null, "0.1.0", "engine-y").getStatus());
        assertEquals(400, r.engine(DEFINITION.definitionId(), DEFINITION.version(), " ").getStatus());
        r.engine = new WorkflowEngine() {
            public boolean hasDefinition(DefinitionRef d) { throw new AssertionError(); }
            public EngineLaunch prepare(DefinitionRef d, Map<String,Object> input) { throw new AssertionError(); }
            public Optional<ActiveInstance> activeInstance(DefinitionRef d, String id) { throw new IllegalStateException("PRIVATE-CANARY"); }
        };
        try (var response = r.engine(DEFINITION.definitionId(), DEFINITION.version(), "engine-y")) {
            assertEquals(503, response.getStatus());
            assertEquals(new DevLifecycleResource.EngineProbe(false, "UNAVAILABLE"), response.getEntity());
        }
    }
    @Test void asynchronousPublishFailureIsObservedWithoutBlockingOrLoggingPrivateExceptionText() throws Exception {
        var future = new CompletableFuture<Void>();
        var messages = new ArrayList<String>();
        var logger = Logger.getLogger(DevLifecycleResource.class.getName());
        var handler = new Handler() {
            public void publish(LogRecord record) { messages.add(record.getMessage()); }
            public void flush() { }
            public void close() { }
        };
        logger.addHandler(handler);
        var publisher = new EventPublisher() {
            public CompletableFuture<Void> publish(CloudEvent event) { return future; }
            public void close() { }
        };
        try (var app = WorkflowApplication.builder().withEventPublisher(publisher)
                .disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            var r = resource(); r.application = app;
            assertEquals(202, r.publish(Map.of("type", "PRIVATE-EVENT-TYPE")).getStatus());
            assertFalse(future.isDone(), "HTTP acceptance must not await delivery");
            future.completeExceptionally(new IllegalStateException("PRIVATE-CANARY"));
            assertTrue(messages.stream().anyMatch(m -> m.contains("probe-event delivery failed")));
            assertTrue(messages.stream().noneMatch(m -> m.contains("PRIVATE-CANARY") || m.contains("PRIVATE-EVENT-TYPE")));
        } finally { logger.removeHandler(handler); }
    }
}
