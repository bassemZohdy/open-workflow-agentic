package io.github.bassemzohdy.openworkflow.agentic.runtime.api;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Optional agent-entry tracing; only a single W3C traceparent is accepted, never baggage or tracestate. */
@ApplicationScoped
public class AgentRequestTelemetry {
    private static final TextMapGetter<String> PARENT_ONLY = new TextMapGetter<>() {
        @Override public Iterable<String> keys(String carrier) { return List.of("traceparent"); }
        @Override public String get(String carrier, String key) { return "traceparent".equalsIgnoreCase(key) ? carrier : null; }
    };
    private final Tracer tracer;
    private final boolean acceptRemoteParent;
    @Inject public AgentRequestTelemetry(OpenTelemetry telemetry,
            @ConfigProperty(name = "agentic.observability.accept-agent-traceparent", defaultValue = "false") boolean acceptRemoteParent) {
        this(telemetry.getTracer("io.github.bassemzohdy.openworkflow.agent-entry", "1.0"), acceptRemoteParent);
    }
    public AgentRequestTelemetry(Tracer tracer, boolean acceptRemoteParent) {
        this.tracer = Objects.requireNonNull(tracer); this.acceptRemoteParent = acceptRemoteParent;
    }
    static AgentRequestTelemetry noop() { return new AgentRequestTelemetry(OpenTelemetry.noop().getTracer("agent-entry"), false); }

    <T> T call(HttpHeaders headers, Supplier<T> work) {
        Objects.requireNonNull(headers); Objects.requireNonNull(work);
        Context parent = Context.root();
        try {
            var values = acceptRemoteParent ? headers.getRequestHeader("traceparent") : null;
            if (values != null && values.size() == 1 && values.get(0) != null && values.get(0).length() <= 256)
                parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), values.get(0), PARENT_ONLY);
        } catch (RuntimeException ignored) { /* Invalid or unavailable tracing input never grants or denies authority. */ }
        Span span;
        try { span = tracer.spanBuilder("oa.agent.request").setSpanKind(SpanKind.SERVER).setParent(parent).startSpan(); }
        catch (RuntimeException unavailable) { return withoutAmbientContext(parent, work); }
        Scope scope;
        try { scope = parent.with(span).makeCurrent(); }
        catch (RuntimeException unavailable) {
            try { span.end(); } catch (RuntimeException ignored) { }
            return withoutAmbientContext(parent, work);
        }
        try {
            T result = work.get(); outcome(span, "completed"); return result;
        } catch (RuntimeException | Error failure) {
            outcome(span, "failed"); throw failure;
        } finally {
            try { scope.close(); } catch (RuntimeException ignored) { }
            finally { try { span.end(); } catch (RuntimeException ignored) { } }
        }
    }
    private static <T> T withoutAmbientContext(Context parent, Supplier<T> work) {
        Scope scope;
        try { scope = parent.makeCurrent(); }
        catch (RuntimeException unavailable) { return work.get(); }
        try { return work.get(); }
        finally { try { scope.close(); } catch (RuntimeException ignored) { } }
    }
    private static void outcome(Span span, String value) {
        try {
            span.setAttribute("oa.agent.request.schema", "1");
            span.setAttribute("oa.agent.request.outcome", value);
            if ("failed".equals(value)) span.setStatus(StatusCode.ERROR, "agent execution did not complete");
        } catch (RuntimeException ignored) { }
    }
}
