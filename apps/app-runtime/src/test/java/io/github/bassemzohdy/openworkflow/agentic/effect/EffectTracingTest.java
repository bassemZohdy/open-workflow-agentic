package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.enterprise.inject.Alternative;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Actual ledger/service execution with the real OTel SDK; external targets are explicit component doubles. */
@QuarkusTest
@io.quarkus.test.junit.TestProfile(EffectTracingTest.Profile.class)
class EffectTracingTest {
    @Inject JdbcEffectLedger ledger;
    @Inject DurableEffects liveService;
    @Inject OpenTelemetry liveTelemetry;
    public static class Profile implements io.quarkus.test.junit.QuarkusTestProfile {
        public Map<String,String> getConfigOverrides() {return Map.of("quarkus.otel.sdk.disabled","false",
                "quarkus.otel.traces.sampler","always_on","quarkus.otel.bsp.schedule.delay","10ms");}
        public Set<Class<?>> getEnabledAlternatives() {return Set.of(TestExporter.class);}
    }
    @Alternative @Singleton
    public static class TestExporter implements SpanExporter {
        static final InMemorySpanExporter EXPORTED=InMemorySpanExporter.create();
        public CompletableResultCode export(Collection<SpanData> spans) {return EXPORTED.export(spans);}
        public CompletableResultCode flush() {return CompletableResultCode.ofSuccess();}
        public CompletableResultCode shutdown() {return CompletableResultCode.ofSuccess();}
    }
    @Test void configuredCdiServiceExportsItsActualEffectSpansThroughTheExtension() throws Exception {
        var command=JdbcEffectLedgerTest.command("cdi-traced-charge");
        var parent=liveTelemetry.getTracer("effect-test").spanBuilder("test-parent").startSpan();
        assertTrue(parent.getSpanContext().isValid(),"test profile must enable the real SDK");
        try(var scope=parent.makeCurrent()) {
            assertEquals(EffectState.CONFIRMED,liveService.execute(command,new EffectTarget() {
                public String target() {return command.target();}
                public Reply execute(String key) {return new Reply(Outcome.CONFIRMED,"private-target-reply");}
            }).state());
        } finally {parent.end();}
        assertTimeoutPreemptively(Duration.ofSeconds(5),()->{
            while(TestExporter.EXPORTED.getFinishedSpanItems().stream().filter(s->s.getTraceId().equals(parent.getSpanContext().getTraceId())).count()<3)
                Thread.sleep(10);
        });
        var spans=TestExporter.EXPORTED.getFinishedSpanItems().stream().filter(s->s.getTraceId().equals(parent.getSpanContext().getTraceId())).toList();
        assertEquals(Set.of("test-parent","oa.effect.execute","oa.effect.dispatch"),new HashSet<>(spans.stream().map(SpanData::getName).toList()));
        for(var span:spans) assertFalse(span.toString().contains("private-target-reply"));
    }
    @Test void realLedgerExecutionAndCompensationEmitSafeRelatedSpans() {
        var exported=InMemorySpanExporter.create();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build()) {
            var service=new DurableEffects(ledger,Clock.systemUTC(),new EffectTelemetry(provider.get("effects")));
            var command=JdbcEffectLedgerTest.command("traced-charge");var calls=new AtomicInteger();
            EffectTarget target=new EffectTarget() {
                public String target() {return command.target();}
                public Reply execute(String key) {calls.incrementAndGet();return new Reply(Outcome.CONFIRMED,"private-reply");}
                public Reply lookup(String key) {throw new AssertionError("new intent must dispatch");}
            };
            assertEquals(EffectState.CONFIRMED,service.execute(command,target).state());
            var refund=EffectCommand.of(command.key().threadId(),"refund","1",command.target(),"private-request");
            assertEquals(EffectState.COMPENSATED,service.compensate(command.key(),refund,target).state());
            assertEquals(2,calls.get());assertEquals(4,exported.getFinishedSpanItems().size());
            for(var span:exported.getFinishedSpanItems()) {
                assertFalse(span.toString().contains("private-"));assertFalse(span.toString().contains(command.key().idempotencyKey()));
                if(span.getName().equals("oa.effect.dispatch")) assertTrue(exported.getFinishedSpanItems().stream()
                        .anyMatch(parent->parent.getSpanId().equals(span.getParentSpanId())));
            }
        }
    }
    @Test void anAttemptedIntentIsLookedUpWithoutAnotherDispatchAndHasARecoverySpan() {
        var command=JdbcEffectLedgerTest.command("traced-recovery");ledger.prepare(command,Duration.ZERO,Instant.now());ledger.markAttempted(command.key());
        var exported=InMemorySpanExporter.create();var lookups=new AtomicInteger();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build()) {
            var service=new DurableEffects(ledger,Clock.systemUTC(),new EffectTelemetry(provider.get("effects")));
            EffectTarget target=new EffectTarget() {
                public String target() {return command.target();}
                public Reply execute(String key) {throw new AssertionError("recovery must not redispatch");}
                public Reply lookup(String key) {lookups.incrementAndGet();return new Reply(Outcome.CONFIRMED,"private-lookup");}
            };
            assertEquals(EffectState.CONFIRMED,service.execute(command,target).state());assertEquals(1,lookups.get());
            assertEquals(2,exported.getFinishedSpanItems().size());
            var lookup=exported.getFinishedSpanItems().stream().filter(s->s.getName().equals("oa.effect.lookup")).findFirst().orElseThrow();
            assertEquals("confirmed",lookup.getAttributes().get(AttributeKey.stringKey("oa.effect.outcome")));
        }
    }
}
