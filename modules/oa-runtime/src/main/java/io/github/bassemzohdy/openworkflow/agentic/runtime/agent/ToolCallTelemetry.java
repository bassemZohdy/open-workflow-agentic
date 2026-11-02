package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Objects;
import java.util.function.Supplier;

/** Explicit optional tool tracing. No tool name, arguments, output, identity or exception details are recorded. */
public final class ToolCallTelemetry {
    @FunctionalInterface interface Work<T> { T run() throws Exception; }
    private final Tracer tracer;
    public ToolCallTelemetry(OpenTelemetry telemetry) {
        this(telemetry.getTracer("io.github.bassemzohdy.openworkflow.tools", "1.0"));
    }
    public ToolCallTelemetry(Tracer tracer) { this.tracer = Objects.requireNonNull(tracer); }
    static ToolCallTelemetry noop() { return new ToolCallTelemetry(OpenTelemetry.noop()); }

    <T> T call(Work<T> work) throws Exception {
        Objects.requireNonNull(work);
        Span span;
        try { span = tracer.spanBuilder("oa.tool.call").setSpanKind(SpanKind.INTERNAL).startSpan(); }
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
    <T> T callUnchecked(Supplier<T> work) {
        try { return call(work::get); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static void outcome(Span span, String value) {
        try {
            span.setAttribute("oa.tool.schema", "1");
            span.setAttribute("oa.tool.outcome", value);
            if ("failed".equals(value)) span.setStatus(StatusCode.ERROR, "tool call did not complete");
        } catch (RuntimeException ignored) { }
    }
}
