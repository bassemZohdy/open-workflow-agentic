package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Effect;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectCommand;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectKey;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.EffectState;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget.Reply;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import org.jboss.logging.Logger;

/**
 * Opt-in execution boundary for governed adapters. Does not intercept arbitrary Flow HTTP tasks. Unknown outcomes
 * stay INTENDED; callers must test the returned state before continuing. No credential/policy/target is inferred from
 * user input. Adapters own authorization, read-only lookup and finite network timeouts.
 * Observed thread interruption throws CancellationException with the flag preserved. Committed intent/attempt/outcome
 * facts stay retained; cancellation is never proof of non-application and must not cause a retry under a new key.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class DurableEffects {
    private static final Logger LOG = Logger.getLogger(DurableEffects.class);
    private final JdbcEffectLedger ledger;
    private final Clock clock;
    private final EffectTelemetry telemetry;
    @Inject MeterRegistry metrics;
    // Each active call uses a claim connection and short transaction connections. Avoid unbounded pool claims.
    private final Semaphore slots = new Semaphore(4);

    @Inject
    public DurableEffects(JdbcEffectLedger ledger,EffectTelemetry telemetry) { this(ledger,Clock.systemUTC(),telemetry); }
    public DurableEffects(JdbcEffectLedger ledger) {this(ledger,Clock.systemUTC());}
    public DurableEffects(JdbcEffectLedger ledger, Clock clock) {
        this(ledger,clock,EffectTelemetry.noop());
    }
    public DurableEffects(JdbcEffectLedger ledger,Clock clock,EffectTelemetry telemetry) {
        this.ledger = Objects.requireNonNull(ledger); this.clock = Objects.requireNonNull(clock);this.telemetry=Objects.requireNonNull(telemetry);
    }

    public Effect execute(EffectCommand command, EffectTarget target) {
        Effect result=telemetry.effect(EffectTelemetry.Operation.EXECUTE,()->executeCommand(command,target));
        requireNotInterrupted(); // Claim release and optional tracing cleanup can block after the inner outcome check.
        return result;
    }

    private Effect executeCommand(EffectCommand command,EffectTarget target) {
        requireNotInterrupted();
        requireTarget(command, target);
        requireNotInterrupted();
        if (!slots.tryAcquire()) throw new JdbcEffectLedger.EffectBusyException();
        try { return ledger.exclusively(command.key(), () -> {requireNotInterrupted();return executeLocked(command, target);}); }
        finally { slots.release(); }
    }

    private Effect executeLocked(EffectCommand command, EffectTarget target) {
        Duration window = Objects.requireNonNull(target.replayWindow(), "replayWindow");
        requireNotInterrupted();
        JdbcEffectLedger.Prepared prepared = ledger.prepare(command, window, clock.instant());
        return executePrepared(command, target, prepared);
    }

    private Effect executePrepared(EffectCommand command, EffectTarget target, JdbcEffectLedger.Prepared prepared) {
        requireNotInterrupted();
        Duration window = Objects.requireNonNull(target.replayWindow(), "replayWindow");
        requireNotInterrupted();
        Effect intent = prepared.effect();
        if (intent.state().isResolved()) return intent;
        Reply result;
        if (!prepared.attempted()) {
            ledger.markAttempted(command.key()); // This commit MUST succeed before dispatch.
            result = dispatch(target, command.key());
        } else {
            result = lookup(target, command.key());
            Instant now = clock.instant();
            if (result.outcome() == EffectTarget.Outcome.UNKNOWN) requireNotInterrupted();
            if (result.outcome() == EffectTarget.Outcome.UNKNOWN && prepared.replayUntil() != null
                    && now.isBefore(prepared.replayUntil()) && !window.isZero()
                    && now.isBefore(prepared.createdAt().plus(window))) {
                result = dispatch(target, command.key());
            }
        }
        if (result.outcome() == EffectTarget.Outcome.UNKNOWN) {
            requireNotInterrupted();
            ledger.requireManualResolution(command.key(), "effect.outcome.unknown");
            count("unknown");
            LOG.warnf("Effect awaits reconciliation: thread=%s node=%s", command.key().threadId(), command.key().nodeId());
            requireNotInterrupted();
            Effect pending=ledger.find(command.key()).orElseThrow();
            requireNotInterrupted();
            return pending;
        }
        // Retain an observed target fact even if cancellation arrived during its I/O. This is history, not advancement.
        Effect resultEffect = ledger.recordOutcome(command.key(), result.outcome() == EffectTarget.Outcome.CONFIRMED
                ? EffectState.CONFIRMED : EffectState.FAILED, result.detail());
        count(resultEffect.state() == EffectState.CONFIRMED ? "confirmed" : "failed");
        requireNotInterrupted();
        return resultEffect;
    }

    /** Compensation has its own durable intent/key; loss of its acknowledgment is recovered like any other effect. */
    public Effect compensate(EffectKey original, EffectCommand compensation, EffectTarget target) {
        Effect result=telemetry.effect(EffectTelemetry.Operation.COMPENSATE,()->compensateCommand(original,compensation,target));
        requireNotInterrupted();
        return result;
    }

    private Effect compensateCommand(EffectKey original,EffectCommand compensation,EffectTarget target) {
        requireNotInterrupted();
        requireTarget(compensation, target);
        requireNotInterrupted();
        if (!original.threadId().equals(compensation.key().threadId()) || original.equals(compensation.key())) {
            throw new IllegalArgumentException("compensation requires another key in the original scope");
        }
        if (!slots.tryAcquire()) throw new JdbcEffectLedger.EffectBusyException();
        try {
            return ledger.exclusively(original, () -> {
                requireNotInterrupted();
                Effect before = ledger.find(original).orElseThrow();
                requireNotInterrupted();
                if (before.state() == EffectState.INTENDED) throw new IllegalStateException("resolve original first");
                Effect undo = ledger.exclusively(compensation.key(), () -> {
                    requireNotInterrupted();
                    Duration window=Objects.requireNonNull(target.replayWindow(),"replayWindow");
                    requireNotInterrupted();
                    JdbcEffectLedger.Prepared prepared = ledger.prepareCompensation(original, compensation,
                            window, clock.instant());
                    requireNotInterrupted();
                    if (before.state() == EffectState.COMPENSATED) return prepared.effect();
                    return executePrepared(compensation, target, prepared);
                });
                if (before.state() == EffectState.COMPENSATED) {requireNotInterrupted();return before;}
                if (undo.state() == EffectState.CONFIRMED) {
                    Effect result = ledger.recordOutcome(original, EffectState.COMPENSATED, "compensation.confirmed");
                    count("compensated");
                    requireNotInterrupted();
                    return result;
                }
                requireNotInterrupted();
                ledger.requireManualResolution(original, "effect.compensation.unresolved");
                requireNotInterrupted();
                Effect pending=ledger.find(original).orElseThrow();
                requireNotInterrupted();
                return pending;
            });
        } finally { slots.release(); }
    }

    private void count(String outcome) {
        if (metrics != null) metrics.counter("agentic.effects.outcomes", "outcome", outcome).increment();
    }

    private static void requireTarget(EffectCommand command, EffectTarget target) {
        Objects.requireNonNull(command, "command"); Objects.requireNonNull(target, "target");
        if (!command.target().equals(target.target())) throw new IllegalArgumentException("adapter target mismatch");
    }

    /** Cooperative thread cancellation only; it is not a substitute for registry, lease or current identity checks. */
    private static void requireNotInterrupted() {
        if(Thread.currentThread().isInterrupted()) throw new CancellationException("effect caller interrupted");
    }

    private Reply dispatch(EffectTarget target, EffectKey key) {
        return telemetry.target(EffectTelemetry.Operation.DISPATCH,()->{
            requireNotInterrupted(); // After blocking intent/attempt writes and telemetry setup, immediately before target I/O.
            try { return Objects.requireNonNull(target.execute(key.idempotencyKey()), "target reply"); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return new Reply(EffectTarget.Outcome.UNKNOWN, null);
            } catch (Exception e) { return new Reply(EffectTarget.Outcome.UNKNOWN, null); }
        });
    }

    private Reply lookup(EffectTarget target, EffectKey key) {
        return telemetry.target(EffectTelemetry.Operation.LOOKUP,()->{
            requireNotInterrupted();
            try { return Objects.requireNonNull(target.lookup(key.idempotencyKey()), "lookup reply"); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return new Reply(EffectTarget.Outcome.UNKNOWN, null);
            } catch (Exception e) { return new Reply(EffectTarget.Outcome.UNKNOWN, null); }
        });
    }
}
