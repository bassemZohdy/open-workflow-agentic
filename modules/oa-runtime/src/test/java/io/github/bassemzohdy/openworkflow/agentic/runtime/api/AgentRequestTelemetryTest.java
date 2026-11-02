package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AgentResourceAdmission;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentRequestTelemetryTest {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String UPSTREAM = "0123456789abcdef";
    private static final String PRIVATE = "private-bearer-topic-output";
    private static final class MovingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
    private static HttpHeaders headers(List<String> authorization, List<String> parent) {
        return (HttpHeaders)Proxy.newProxyInstance(HttpHeaders.class.getClassLoader(),new Class<?>[]{HttpHeaders.class},
                (p,m,a)->{
                    if(m.getName().equals("getRequestHeader")) {
                        if("Authorization".equalsIgnoreCase((String)a[0]))return authorization;
                        if("traceparent".equalsIgnoreCase((String)a[0]))return parent;
                        if("baggage".equalsIgnoreCase((String)a[0]) || "tracestate".equalsIgnoreCase((String)a[0]))
                            return List.of("secret="+PRIVATE);
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }
    private static AgentResource resource(MovingClock clock, ResearchAgent agent, AgentRequestTelemetry telemetry) {
        var token=new VerifiedToken("issuer",new Subject(SubjectKind.USER,"alice"),Set.of("oa-api"),
                Set.of(AgentResourceAdmission.SCOPE),"tenant",NOW,NOW.plusSeconds(60),"jti",List.of());
        var resolver=new CallerContextResolver((bearer,audience)->token,"oa-api",false,Set.of("test"),Set.of("test"),clock);
        var pep=new Pep(request->new Decision("test",DecisionOutcome.ALLOW,"allow",new PolicyRef("test","1"),clock.instant()),event->{});
        var resource=new AgentResource(agent,resolver,pep,new ObjectMapper(),new AgentResourceAdmission(null,null,null,null,clock));
        resource.telemetry=telemetry;return resource;
    }

    @Test void verifiedRequestConnectsOnlyTheW3cParentAndRestoresTheCallerContext() {
        var exporter=InMemorySpanExporter.create();
        try(var sdk=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var calls=new AtomicInteger();
            var agent=new ResearchAgent(){@Override public ResearchState run(String id,String topic) {
                calls.incrementAndGet();assertNull(Baggage.current().getEntryValue("secret"));
                var child=sdk.get("graph").spanBuilder("node").startSpan();child.end();
                return new ResearchState(Map.of(ResearchState.TOPIC,topic));
            }};
            var resource=resource(new MovingClock(),agent,new AgentRequestTelemetry(sdk.get("entry"),true));
            var ambient=sdk.get("ambient").spanBuilder("ambient").startSpan();
            var baggage=Baggage.builder().put("secret",PRIVATE).build();
            try(var scope=Context.current().with(ambient).with(baggage).makeCurrent()) {
                assertEquals(PRIVATE,resource.run(headers(List.of("Bearer "+PRIVATE),
                        List.of("00-"+TRACE+"-"+UPSTREAM+"-01")),"{\"topic\":\""+PRIVATE+"\"}").get("topic"));
                assertEquals(ambient.getSpanContext(),Span.current().getSpanContext());
                assertEquals(PRIVATE,Baggage.current().getEntryValue("secret"));
            } finally {ambient.end();}
            var spans=exporter.getFinishedSpanItems();
            var entry=spans.stream().filter(s->s.getName().equals("oa.agent.request")).findFirst().orElseThrow();
            var node=spans.stream().filter(s->s.getName().equals("node")).findFirst().orElseThrow();
            assertEquals(TRACE,entry.getTraceId());assertEquals(UPSTREAM,entry.getParentSpanId());
            assertEquals(SpanKind.SERVER,entry.getKind());assertEquals(entry.getSpanId(),node.getParentSpanId());
            assertEquals(Set.of(AttributeKey.stringKey("oa.agent.request.schema"),AttributeKey.stringKey("oa.agent.request.outcome")),
                    entry.getAttributes().asMap().keySet());
            assertFalse(entry.toString().contains(PRIVATE));assertTrue(entry.getEvents().isEmpty());assertEquals(1,calls.get());
        }
    }

    @Test void duplicateParentsAreIgnoredAndUnauthenticatedCallsCreateNoSpan() {
        var exporter=InMemorySpanExporter.create();
        try(var sdk=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var agent=new ResearchAgent(){@Override public ResearchState run(String id,String topic) {return new ResearchState(Map.of());}};
            var resource=resource(new MovingClock(),agent,new AgentRequestTelemetry(sdk.get("entry"),true));
            assertThrows(WebApplicationException.class,()->resource.run(headers(null,List.of("00-"+TRACE+"-"+UPSTREAM+"-01")),"{\"topic\":\"x\"}"));
            assertTrue(exporter.getFinishedSpanItems().isEmpty());
            resource.run(headers(List.of("Bearer valid"),List.of("00-"+TRACE+"-"+UPSTREAM+"-01","00-"+TRACE+"-"+UPSTREAM+"-01")),"{\"topic\":\"x\"}");
            var entry=exporter.getFinishedSpanItems().get(0);
            assertNotEquals(TRACE,entry.getTraceId());assertEquals("0000000000000000",entry.getParentSpanId());
        }
    }

    @Test void remoteParentIsIgnoredWhenNotExplicitlyEnabled() {
        var exporter=InMemorySpanExporter.create();
        try(var sdk=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var agent=new ResearchAgent(){@Override public ResearchState run(String id,String topic){return new ResearchState(Map.of());}};
            var resource=resource(new MovingClock(),agent,new AgentRequestTelemetry(sdk.get("entry"),false));
            resource.run(headers(List.of("Bearer valid"),List.of("00-"+TRACE+"-"+UPSTREAM+"-01")),"{\"topic\":\"x\"}");
            var entry=exporter.getFinishedSpanItems().get(0);
            assertNotEquals(TRACE,entry.getTraceId());assertEquals("0000000000000000",entry.getParentSpanId());
        }
    }

    @Test void callerExpiryDuringTraceSetupPreventsTheGraph() {
        var clock=new MovingClock();var calls=new AtomicInteger();
        var agent=new ResearchAgent(){@Override public ResearchState run(String id,String topic){calls.incrementAndGet();return new ResearchState(Map.of());}};
        var tracer=(Tracer)Proxy.newProxyInstance(Tracer.class.getClassLoader(),new Class<?>[]{Tracer.class},(p,m,a)->{
            clock.now.set(NOW.plusSeconds(60));throw new IllegalStateException(PRIVATE);
        });
        var resource=resource(clock,agent,new AgentRequestTelemetry(tracer,true));
        var error=assertThrows(WebApplicationException.class,()->resource.run(headers(List.of("Bearer valid"),null),"{\"topic\":\"x\"}"));
        assertEquals(401,error.getResponse().getStatus());assertEquals(0,calls.get());
    }

    @Test void tracingFailureDoesNotPassAmbientBaggageToTheGraph() {
        var agent=new ResearchAgent(){@Override public ResearchState run(String id,String topic){
            assertNull(Baggage.current().getEntryValue("secret"));return new ResearchState(Map.of());
        }};
        var tracer=(Tracer)Proxy.newProxyInstance(Tracer.class.getClassLoader(),new Class<?>[]{Tracer.class},
                (p,m,a)->{throw new IllegalStateException(PRIVATE);});
        var resource=resource(new MovingClock(),agent,new AgentRequestTelemetry(tracer,true));
        try(var scope=Context.current().with(Baggage.builder().put("secret",PRIVATE).build()).makeCurrent()) {
            resource.run(headers(List.of("Bearer valid"),null),"{\"topic\":\"x\"}");
            assertEquals(PRIVATE,Baggage.current().getEntryValue("secret"));
        }
    }

    @Test void callerExpiryDuringSpanCompletionWithholdsTheOutput() {
        var clock=new MovingClock();var calls=new AtomicInteger();
        var agent=new ResearchAgent(){@Override public ResearchState run(String id,String topic){calls.incrementAndGet();return new ResearchState(Map.of());}};
        try(var sdk=SdkTracerProvider.builder().build()) {
            var real=sdk.get("test").spanBuilder("delegate").startSpan();
            var span=(Span)Proxy.newProxyInstance(Span.class.getClassLoader(),new Class<?>[]{Span.class},(p,m,a)->{
                if(m.getName().equals("end"))clock.now.set(NOW.plusSeconds(60));
                try{return m.invoke(real,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
            var builder=(SpanBuilder)Proxy.newProxyInstance(SpanBuilder.class.getClassLoader(),new Class<?>[]{SpanBuilder.class},
                    (p,m,a)->m.getName().equals("startSpan")?span:p);
            var tracer=(Tracer)Proxy.newProxyInstance(Tracer.class.getClassLoader(),new Class<?>[]{Tracer.class},(p,m,a)->builder);
            var resource=resource(clock,agent,new AgentRequestTelemetry(tracer,true));
            var error=assertThrows(WebApplicationException.class,()->resource.run(headers(List.of("Bearer valid"),null),"{\"topic\":\"x\"}"));
            assertEquals(401,error.getResponse().getStatus());assertEquals(1,calls.get());
        }
    }
}
