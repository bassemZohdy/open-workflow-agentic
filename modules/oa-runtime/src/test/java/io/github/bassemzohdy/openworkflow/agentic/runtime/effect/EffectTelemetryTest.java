package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EffectTelemetryTest {
    private static final String SECRET="private-payload-and-credential";
    private static Effect effect(EffectState state) {
        var command=EffectCommand.of(ThreadId.derive(SECRET,"alice","instance"),"node","1","test:"+SECRET,SECRET);
        return new Effect(command.key().threadId(),command.key().nodeId(),command.key().idempotencyKey(),command.target(),state,SECRET,Instant.now());
    }
    @Test void actualSdkPreservesParentChildAndContextWithoutExportingSensitiveData() {
        var exporter=InMemorySpanExporter.create();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry=new EffectTelemetry(provider.get("test"));var tracer=provider.get("parent");
            var parent=tracer.spanBuilder("parent").startSpan();var expected=effect(EffectState.CONFIRMED);
            try(var scope=parent.makeCurrent()) {
                assertSame(expected,telemetry.effect(EffectTelemetry.Operation.EXECUTE,()->{
                    assertNotEquals(parent.getSpanContext().getSpanId(),Span.current().getSpanContext().getSpanId());
                    telemetry.target(EffectTelemetry.Operation.DISPATCH,()->new EffectTarget.Reply(EffectTarget.Outcome.CONFIRMED,SECRET));
                    return expected;
                }));
                assertEquals(parent.getSpanContext(),Span.current().getSpanContext());
            } finally {parent.end();}
            var spans=exporter.getFinishedSpanItems();assertEquals(3,spans.size());
            var logical=spans.stream().filter(s->s.getName().equals("oa.effect.execute")).findFirst().orElseThrow();
            var client=spans.stream().filter(s->s.getName().equals("oa.effect.dispatch")).findFirst().orElseThrow();
            assertEquals(parent.getSpanContext().getSpanId(),logical.getParentSpanId());
            assertEquals(logical.getSpanId(),client.getParentSpanId());assertEquals(logical.getTraceId(),client.getTraceId());
            assertEquals(SpanKind.CLIENT,client.getKind());
            for(var span:List.of(logical,client)) {
                assertEquals(Set.of(AttributeKey.stringKey("oa.effect.schema"),AttributeKey.stringKey("oa.effect.outcome")),span.getAttributes().asMap().keySet());
                assertEquals("confirmed",span.getAttributes().get(AttributeKey.stringKey("oa.effect.outcome")));
                assertFalse(span.toString().contains(SECRET));assertTrue(span.getEvents().isEmpty());
            }
        }
    }
    @Test void unresolvedAndFailedOutcomesAreErrorsWithoutExceptionDetails() {
        var exporter=InMemorySpanExporter.create();
        try(var provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry=new EffectTelemetry(provider.get("test"));
            telemetry.effect(EffectTelemetry.Operation.COMPENSATE,()->effect(EffectState.INTENDED));
            telemetry.target(EffectTelemetry.Operation.LOOKUP,()->new EffectTarget.Reply(EffectTarget.Outcome.UNKNOWN,SECRET));
            var failure=new IllegalStateException(SECRET);
            assertSame(failure,assertThrows(IllegalStateException.class,()->telemetry.effect(EffectTelemetry.Operation.EXECUTE,()->{throw failure;})));
            for(var span:exporter.getFinishedSpanItems()) {
                assertEquals(StatusCode.ERROR,span.getStatus().getStatusCode());
                assertFalse(span.toString().contains(SECRET));assertTrue(span.getEvents().isEmpty());
            }
        }
    }
    @Test void aBrokenSpanFactoryDoesNotRetryOrReplaceBusinessWork() {
        var tracer=(Tracer)Proxy.newProxyInstance(Tracer.class.getClassLoader(),new Class<?>[]{Tracer.class},
                (p,m,args)->{throw new IllegalStateException(SECRET);});
        var telemetry=new EffectTelemetry(tracer);var calls=new AtomicInteger();var expected=effect(EffectState.CONFIRMED);
        assertSame(expected,telemetry.effect(EffectTelemetry.Operation.EXECUTE,()->{calls.incrementAndGet();return expected;}));
        var failure=new IllegalStateException("business failure");
        assertSame(failure,assertThrows(IllegalStateException.class,()->telemetry.effect(EffectTelemetry.Operation.EXECUTE,()->{calls.incrementAndGet();throw failure;})));
        assertEquals(2,calls.get());
    }
    @Test void failingAttributesAndSpanEndCannotChangeOutcomeOrLeakCurrentContext() {
        var span=(Span)Proxy.newProxyInstance(Span.class.getClassLoader(),new Class<?>[]{Span.class},(p,m,args)->{
            if(m.getName().equals("getSpanContext")) return SpanContext.getInvalid();
            throw new IllegalStateException(SECRET);
        });
        var builder=(SpanBuilder)Proxy.newProxyInstance(SpanBuilder.class.getClassLoader(),new Class<?>[]{SpanBuilder.class},
                (p,m,args)->m.getName().equals("startSpan")?span:p);
        var tracer=(Tracer)Proxy.newProxyInstance(Tracer.class.getClassLoader(),new Class<?>[]{Tracer.class},(p,m,args)->builder);
        var previous=Span.current();var expected=effect(EffectState.CONFIRMED);
        assertSame(expected,new EffectTelemetry(tracer).effect(EffectTelemetry.Operation.EXECUTE,()->expected));
        assertSame(previous,Span.current());
    }
    @Test void optionalOutcomeInspectionDoesNotChangeNullReturn() {
        assertNull(new EffectTelemetry(OpenTelemetry.noop()).target(EffectTelemetry.Operation.LOOKUP,()->null));
    }
}
