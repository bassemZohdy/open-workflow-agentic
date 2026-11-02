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
import java.util.function.Supplier;

/** Optional model-call tracing. It never records prompts, output, identities, model names or exception details. */
@ApplicationScoped
public class ModelCallTelemetry {
    private final Tracer tracer;
    @Inject public ModelCallTelemetry(OpenTelemetry telemetry) {
        this(telemetry.getTracer("io.github.bassemzohdy.openworkflow.model", "1.0"));
    }
    public ModelCallTelemetry(Tracer tracer) { this.tracer = Objects.requireNonNull(tracer); }
    static ModelCallTelemetry noop() { return new ModelCallTelemetry(OpenTelemetry.noop()); }

    <T> T call(Supplier<T> work) {
        Objects.requireNonNull(work);
        Span span;
        try { span = tracer.spanBuilder("oa.model.call").setSpanKind(SpanKind.INTERNAL).startSpan(); }
        catch (RuntimeException unavailable) { return work.get(); }
        Scope scope;
        try { scope = Context.current().with(span).makeCurrent(); }
        catch (RuntimeException unavailable) {
            try { span.end(); } catch (RuntimeException ignored) { }
            return work.get();
        }
        try {
            T result = work.get();
            outcome(span, "completed");
            return result;
        } catch (RuntimeException | Error failure) {
            outcome(span, "failed");
            throw failure;
        } finally {
            try { scope.close(); } catch (RuntimeException ignored) { }
            finally { try { span.end(); } catch (RuntimeException ignored) { } }
        }
    }
    private static void outcome(Span span, String value) {
        try {
            span.setAttribute("oa.model.schema", "1");
            span.setAttribute("oa.model.outcome", value);
            if ("failed".equals(value)) span.setStatus(StatusCode.ERROR, "model call did not complete");
        } catch (RuntimeException ignored) { }
    }
}
