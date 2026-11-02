package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;

/** Optional graph-node tracing, without node names, state, identities or exception details. */
@ApplicationScoped
public class AgentNodeTelemetry {
    @FunctionalInterface interface Work<T> { T run() throws Exception; }
    private final Tracer tracer;
    @Inject public AgentNodeTelemetry(OpenTelemetry telemetry) {
        this(telemetry.getTracer("io.github.bassemzohdy.openworkflow.graph", "1.0"));
    }
    public AgentNodeTelemetry(Tracer tracer) { this.tracer = Objects.requireNonNull(tracer); }
    static AgentNodeTelemetry noop() { return new AgentNodeTelemetry(OpenTelemetry.noop()); }

    <T> T call(Work<T> work) throws Exception {
        Objects.requireNonNull(work);
        Span span;
        try { span = tracer.spanBuilder("oa.agent.node").setSpanKind(SpanKind.INTERNAL).startSpan(); }
        catch (RuntimeException unavailable) { return work.run(); }
        Scope scope;
        try { scope = Context.current().with(span).makeCurrent(); }
        catch (RuntimeException unavailable) {
            try { span.end(); } catch (RuntimeException ignored) { }
            return work.run();
        }
        try {
            T result = work.run();
            outcome(span, "completed");
            return result;
        } catch (Exception | Error failure) {
            outcome(span, "failed");
            throw failure;
        } finally {
            try { scope.close(); } catch (RuntimeException ignored) { }
            finally { try { span.end(); } catch (RuntimeException ignored) { } }
        }
    }
    private static void outcome(Span span, String value) {
        try {
            span.setAttribute("oa.agent.schema", "1");
            span.setAttribute("oa.agent.outcome", value);
            if ("failed".equals(value)) span.setStatus(StatusCode.ERROR, "node did not complete");
        } catch (RuntimeException ignored) { }
    }
}
