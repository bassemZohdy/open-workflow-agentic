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
import java.util.concurrent.Semaphore;
import org.jboss.logging.Logger;

/**
 * Opt-in execution boundary for governed adapters. Does not intercept arbitrary Flow HTTP tasks. Unknown outcomes
 * stay INTENDED; callers must test the returned state before continuing. No credential/policy/target is inferred from
 * user input. Adapters own authorization, read-only lookup and finite network timeouts.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class DurableEffects {
    private static final Logger LOG = Logger.getLogger(DurableEffects.class);
    private final JdbcEffectLedger ledger;
    private final Clock clock;
    @Inject MeterRegistry metrics;
    // Each active call uses a claim connection and short transaction connections. Avoid unbounded pool claims.
    private final Semaphore slots = new Semaphore(4);

    @Inject
    public DurableEffects(JdbcEffectLedger ledger) { this(ledger, Clock.systemUTC()); }
    public DurableEffects(JdbcEffectLedger ledger, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger); this.clock = Objects.requireNonNull(clock);
    }

    public Effect execute(EffectCommand command, EffectTarget target) {
        requireTarget(command, target);
        if (!slots.tryAcquire()) throw new JdbcEffectLedger.EffectBusyException();
        try { return ledger.exclusively(command.key(), () -> executeLocked(command, target)); }
        finally { slots.release(); }
    }

    private Effect executeLocked(EffectCommand command, EffectTarget target) {
        Duration window = Objects.requireNonNull(target.replayWindow(), "replayWindow");
        JdbcEffectLedger.Prepared prepared = ledger.prepare(command, window, clock.instant());
        return executePrepared(command, target, prepared);
    }

    private Effect executePrepared(EffectCommand command, EffectTarget target, JdbcEffectLedger.Prepared prepared) {
        Duration window = Objects.requireNonNull(target.replayWindow(), "replayWindow");
        Effect intent = prepared.effect();
        if (intent.state().isResolved()) return intent;
        Reply result;
        if (!prepared.attempted()) {
            ledger.markAttempted(command.key()); // This commit MUST succeed before dispatch.
            result = dispatch(target, command.key());
        } else {
            result = lookup(target, command.key());
            Instant now = clock.instant();
            if (!Thread.currentThread().isInterrupted() && result.outcome() == EffectTarget.Outcome.UNKNOWN && prepared.replayUntil() != null
                    && now.isBefore(prepared.replayUntil()) && !window.isZero()
                    && now.isBefore(prepared.createdAt().plus(window))) {
                result = dispatch(target, command.key());
            }
        }
        if (result.outcome() == EffectTarget.Outcome.UNKNOWN) {
            ledger.requireManualResolution(command.key(), "effect.outcome.unknown");
            count("unknown");
            LOG.warnf("Effect awaits reconciliation: thread=%s node=%s", command.key().threadId(), command.key().nodeId());
            return ledger.find(command.key()).orElseThrow();
        }
        Effect resultEffect = ledger.recordOutcome(command.key(), result.outcome() == EffectTarget.Outcome.CONFIRMED
                ? EffectState.CONFIRMED : EffectState.FAILED, result.detail());
        count(resultEffect.state() == EffectState.CONFIRMED ? "confirmed" : "failed");
        return resultEffect;
    }

    /** Compensation has its own durable intent/key; loss of its acknowledgment is recovered like any other effect. */
    public Effect compensate(EffectKey original, EffectCommand compensation, EffectTarget target) {
        requireTarget(compensation, target);
        if (!original.threadId().equals(compensation.key().threadId()) || original.equals(compensation.key())) {
            throw new IllegalArgumentException("compensation requires another key in the original scope");
        }
        if (!slots.tryAcquire()) throw new JdbcEffectLedger.EffectBusyException();
        try {
            return ledger.exclusively(original, () -> {
                Effect before = ledger.find(original).orElseThrow();
                if (before.state() == EffectState.INTENDED) throw new IllegalStateException("resolve original first");
                Effect undo = ledger.exclusively(compensation.key(), () -> {
                    JdbcEffectLedger.Prepared prepared = ledger.prepareCompensation(original, compensation,
                            target.replayWindow(), clock.instant());
                    if (before.state() == EffectState.COMPENSATED) return prepared.effect();
                    return executePrepared(compensation, target, prepared);
                });
                if (before.state() == EffectState.COMPENSATED) return before;
                if (undo.state() == EffectState.CONFIRMED) {
                    Effect result = ledger.recordOutcome(original, EffectState.COMPENSATED, "compensation.confirmed");
                    count("compensated");
                    return result;
                }
                ledger.requireManualResolution(original, "effect.compensation.unresolved");
                return ledger.find(original).orElseThrow();
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

    private static Reply dispatch(EffectTarget target, EffectKey key) {
        try { return Objects.requireNonNull(target.execute(key.idempotencyKey()), "target reply"); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return new Reply(EffectTarget.Outcome.UNKNOWN, null);
        } catch (Exception e) { return new Reply(EffectTarget.Outcome.UNKNOWN, null); }
    }

    private static Reply lookup(EffectTarget target, EffectKey key) {
        try { return Objects.requireNonNull(target.lookup(key.idempotencyKey()), "lookup reply"); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return new Reply(EffectTarget.Outcome.UNKNOWN, null);
        } catch (Exception e) { return new Reply(EffectTarget.Outcome.UNKNOWN, null); }
    }
}
