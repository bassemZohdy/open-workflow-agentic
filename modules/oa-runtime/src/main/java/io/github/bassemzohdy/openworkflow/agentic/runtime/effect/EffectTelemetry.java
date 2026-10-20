package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget.Reply;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Custom effect telemetry schema v1. No payload, key, target, caller, exception message or credential is recorded. */
@ApplicationScoped
public class EffectTelemetry {
    public enum Operation {
        EXECUTE("oa.effect.execute",SpanKind.INTERNAL), COMPENSATE("oa.effect.compensate",SpanKind.INTERNAL),
        DISPATCH("oa.effect.dispatch",SpanKind.CLIENT), LOOKUP("oa.effect.lookup",SpanKind.CLIENT);
        final String name; final SpanKind kind;
        Operation(String name,SpanKind kind) {this.name=name;this.kind=kind;}
    }
    private final Tracer tracer;
    @Inject public EffectTelemetry(OpenTelemetry telemetry) {this(telemetry.getTracer("io.github.bassemzohdy.openworkflow.effects","1.0"));}
    public EffectTelemetry(Tracer tracer) {this.tracer=Objects.requireNonNull(tracer);}
    static EffectTelemetry noop() {return new EffectTelemetry(OpenTelemetry.noop());}

    public Effect effect(Operation operation,Supplier<Effect> work) {
        return traced(operation,work,result->result.state().name().toLowerCase(Locale.ROOT));
    }
    public Reply target(Operation operation,Supplier<Reply> work) {
        return traced(operation,work,result->result.outcome().name().toLowerCase(Locale.ROOT));
    }
    private <T> T traced(Operation operation,Supplier<T> work,Function<T,String> outcome) {
        var observed=begin(operation);
        try {
            T result=work.get();
            try {observed.outcome(outcome.apply(result));} catch(RuntimeException ignored) { }
            return result;
        } catch(RuntimeException failure) {
            observed.outcome(failure instanceof JdbcEffectLedger.EffectBusyException?"busy":"error");
            throw failure; // Preserve the original failure; telemetry never retries or replaces business work.
        } finally {observed.close();}
    }
    private Observation begin(Operation operation) {
        Span span;
        try {span=tracer.spanBuilder(operation.name).setSpanKind(operation.kind).startSpan();}
        catch(RuntimeException unavailable) {return new Observation(Span.getInvalid(),()->{});}
        try {
            // Own the context scope, rather than relying on a custom Span implementation's makeCurrent/close.
            return new Observation(span,Context.current().with(span).makeCurrent());
        } catch(RuntimeException unavailable) {
            try {span.end();} catch(RuntimeException ignored) { }
            return new Observation(Span.getInvalid(),()->{});
        }
    }
    private record Observation(Span span,Scope scope) {
        void outcome(String value) {
            try {
                span.setAttribute("oa.effect.schema","1");span.setAttribute("oa.effect.outcome",value);
                if(!value.equals("confirmed") && !value.equals("compensated"))
                    span.setStatus(StatusCode.ERROR,"effect operation unresolved or refused");
            } catch(RuntimeException ignored) { } // Optional tracing is separate from mandatory ledger/audit commits.
        }
        void close() {
            try {scope.close();} catch(RuntimeException ignored) { }
            finally {try {span.end();} catch(RuntimeException ignored) { }}
        }
    }
}
