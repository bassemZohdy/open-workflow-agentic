package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelCallTelemetryTest {
    private static final String SECRET = "private-prompt-output-token";

    @Test void governedCallCreatesAChildSpanWithoutPrivateData() {
        var exporter = InMemorySpanExporter.create();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var provider = new GovernedLlmClientTest.CountingProvider(n -> SECRET);
            var client = new GovernedLlmClient(provider,
                    GovernedLlmClientTest.pep(new GovernedLlmClientTest.Engine(DecisionOutcome.ALLOW), new GovernedLlmClientTest.Audit()),
                    GovernedLlmClientTest.APPROVED);
            client.telemetry = new ModelCallTelemetry(sdk.get("model-test"));
            var parent = sdk.get("caller").spanBuilder("parent").startSpan();
            try (var ignored = parent.makeCurrent()) {
                assertEquals(SECRET, client.complete(SECRET, SECRET));
                assertEquals(parent.getSpanContext(), Span.current().getSpanContext());
            } finally { parent.end(); }
            var model = exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals("oa.model.call")).findFirst().orElseThrow();
            assertEquals(parent.getSpanContext().getSpanId(), model.getParentSpanId());
            assertEquals(parent.getSpanContext().getTraceId(), model.getTraceId());
            assertEquals(SpanKind.INTERNAL, model.getKind());
            assertEquals(Set.of(AttributeKey.stringKey("oa.model.schema"), AttributeKey.stringKey("oa.model.outcome")),
                    model.getAttributes().asMap().keySet());
            assertEquals("completed", model.getAttributes().get(AttributeKey.stringKey("oa.model.outcome")));
            assertFalse(model.toString().contains(SECRET));
            assertTrue(model.getEvents().isEmpty());
            assertEquals(1, provider.calls.get());
        }
    }

    @Test void deniedCallsDoNotCreateAModelSpanOrReachTheProvider() {
        var exporter = InMemorySpanExporter.create();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var provider = new GovernedLlmClientTest.CountingProvider();
            var client = new GovernedLlmClient(provider,
                    GovernedLlmClientTest.pep(new GovernedLlmClientTest.Engine(DecisionOutcome.DENY), new GovernedLlmClientTest.Audit()),
                    GovernedLlmClientTest.APPROVED);
            client.telemetry = new ModelCallTelemetry(sdk.get("model-test"));
            assertThrows(PolicyDenied.class, () -> client.complete(SECRET, SECRET));
            assertEquals(0, provider.calls.get());
            assertTrue(exporter.getFinishedSpanItems().isEmpty());
        }
    }

    @Test void providerFailuresDoNotExportExceptionDetails() {
        var exporter = InMemorySpanExporter.create();
        try (var sdk = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var provider = new GovernedLlmClientTest.CountingProvider(n -> { throw new IllegalStateException(SECRET); });
            var client = new GovernedLlmClient(provider,
                    GovernedLlmClientTest.pep(new GovernedLlmClientTest.Engine(DecisionOutcome.ALLOW), new GovernedLlmClientTest.Audit()),
                    GovernedLlmClientTest.APPROVED);
            client.telemetry = new ModelCallTelemetry(sdk.get("model-test"));
            assertThrows(GovernedLlmClient.ProviderCallFailed.class, () -> client.complete(SECRET, SECRET));
            var model = exporter.getFinishedSpanItems().get(0);
            assertEquals(StatusCode.ERROR, model.getStatus().getStatusCode());
            assertFalse(model.toString().contains(SECRET));
            assertTrue(model.getEvents().isEmpty());
            assertEquals(1, provider.calls.get());
        }
    }

    @Test void blockingTelemetrySetupCannotBypassTheLastApprovalCheck() {
        var approved = new AtomicReference<>(GovernedLlmClientTest.APPROVED.current());
        var provider = new GovernedLlmClientTest.CountingProvider();
        var client = new GovernedLlmClient(provider,
                GovernedLlmClientTest.pep(new GovernedLlmClientTest.Engine(DecisionOutcome.ALLOW), new GovernedLlmClientTest.Audit()),
                approved::get);
        var broken = (Tracer) Proxy.newProxyInstance(Tracer.class.getClassLoader(), new Class<?>[]{Tracer.class}, (p, m, a) -> {
            approved.set(Optional.empty()); throw new IllegalStateException(SECRET);
        });
        client.telemetry = new ModelCallTelemetry(broken);
        assertThrows(ModelCallRefused.class, () -> client.complete(SECRET, SECRET));
        assertEquals(0, provider.calls.get());
    }

    @Test void unavailableTelemetryDoesNotRetryOrReplaceTheGovernedCall() {
        var provider = new GovernedLlmClientTest.CountingProvider();
        var client = new GovernedLlmClient(provider,
                GovernedLlmClientTest.pep(new GovernedLlmClientTest.Engine(DecisionOutcome.ALLOW), new GovernedLlmClientTest.Audit()),
                GovernedLlmClientTest.APPROVED);
        var broken = (Tracer) Proxy.newProxyInstance(Tracer.class.getClassLoader(), new Class<?>[]{Tracer.class},
                (p, m, a) -> { throw new IllegalStateException(SECRET); });
        client.telemetry = new ModelCallTelemetry(broken);
        assertEquals(GovernedLlmClientTest.OK, client.complete(SECRET, SECRET));
        assertEquals(1, provider.calls.get());
    }

    @Test void approvalLostDuringSpanCompletionWithholdsTheAnswer() {
        var approved = new AtomicReference<>(GovernedLlmClientTest.APPROVED.current());
        var provider = new GovernedLlmClientTest.CountingProvider();
        var client = new GovernedLlmClient(provider,
                GovernedLlmClientTest.pep(new GovernedLlmClientTest.Engine(DecisionOutcome.ALLOW), new GovernedLlmClientTest.Audit()),
                approved::get);
        try (var sdk = SdkTracerProvider.builder().build()) {
            var real = sdk.get("test").spanBuilder("delegate").startSpan();
            var span = (Span) Proxy.newProxyInstance(Span.class.getClassLoader(), new Class<?>[]{Span.class}, (p, m, a) -> {
                if (m.getName().equals("end")) approved.set(Optional.empty());
                try { return m.invoke(real, a); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            });
            var builder = (SpanBuilder) Proxy.newProxyInstance(SpanBuilder.class.getClassLoader(),
                    new Class<?>[]{SpanBuilder.class}, (p, m, a) -> m.getName().equals("startSpan") ? span : p);
            var tracer = (Tracer) Proxy.newProxyInstance(Tracer.class.getClassLoader(), new Class<?>[]{Tracer.class},
                    (p, m, a) -> builder);
            client.telemetry = new ModelCallTelemetry(tracer);
            assertThrows(GovernedLlmClient.ApprovalLostAfterCall.class, () -> client.complete(SECRET, SECRET));
            assertEquals(1, provider.calls.get());
        }
    }
}
