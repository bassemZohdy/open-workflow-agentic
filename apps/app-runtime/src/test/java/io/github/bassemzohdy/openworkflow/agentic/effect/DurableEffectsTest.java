package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.DurableEffects;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.EffectTelemetry;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DurableEffectsTest {
    @Inject JdbcEffectLedger ledger;
    @Inject DataSource dataSource;
    @Inject DurableEffects effects;
    @Inject jakarta.transaction.UserTransaction transaction;

    @Test
    void callerRollbackCannotEraseAnAlreadyDispatchedIntentOrOutcome() throws Exception {
        EffectCommand command = JdbcEffectLedgerTest.command("ambient-rollback"); Target target = new Target();
        transaction.begin();
        try { assertEquals(EffectState.CONFIRMED, effects.execute(command, target).state()); }
        finally { transaction.rollback(); }
        assertEquals(EffectState.CONFIRMED, ledger.find(command.key()).orElseThrow().state());
        assertEquals(1, target.applied.size());
    }

    @Test
    void crashBeforeDispatchResumesOnceAndConflictingRequestCannotDispatch() {
        EffectCommand command = JdbcEffectLedgerTest.command("prepared-only"); Target target = new Target();
        ledger.prepare(command, target.replayWindow(), Instant.now());
        assertEquals(EffectState.CONFIRMED, new DurableEffects(new JdbcEffectLedger(dataSource))
                .execute(command, target).state());
        EffectCommand changed = new EffectCommand(command.key(), command.target(), "a".repeat(64));
        assertThrows(IllegalStateException.class, () -> effects.execute(changed, target));
        assertEquals(1, target.calls.get());
    }

    static class Target implements EffectTarget {
        final Set<String> applied = ConcurrentHashMap.newKeySet();
        final AtomicInteger calls = new AtomicInteger();
        Duration window = Duration.ofHours(1);
        boolean loseAck, refuse;
        public String target() { return "test:payments"; }
        public Duration replayWindow() { return window; }
        public Reply execute(String key) throws Exception {
            calls.incrementAndGet();
            if (refuse) return new Reply(Outcome.FAILED, "refused");
            applied.add(key);
            if (loseAck) { loseAck = false; throw new java.net.SocketTimeoutException(); }
            return new Reply(Outcome.CONFIRMED, "confirmed");
        }
    }

    @Test
    void lostAckReusesOneKeyAndPersistsOneEffectAcrossNewExecutor() {
        EffectCommand command = JdbcEffectLedgerTest.command("lost-ack"); Target target = new Target(); target.loseAck = true;
        assertEquals(EffectState.INTENDED, effects.execute(command, target).state());
        assertEquals(1, ledger.manualQueue(command.key().threadId(), 10).size());
        DurableEffects restarted = new DurableEffects(new JdbcEffectLedger(dataSource));
        assertEquals(EffectState.CONFIRMED, restarted.execute(command, target).state());
        assertEquals(2, target.calls.get()); assertEquals(1, target.applied.size());
        assertEquals(EffectState.CONFIRMED, restarted.execute(command, target).state());
        assertEquals(2, target.calls.get(), "confirmed effects never call the target again");
        assertTrue(ledger.manualQueue(command.key().threadId(), 10).isEmpty());
    }

    @Test
    void lostAuthorityDuringUnknownDispatchWithholdsResultAndQueuesReconciliation() {
        var command = JdbcEffectLedgerTest.command("authority-lost-dispatch");
        var refusal = new AuthorityLostException(AuthorityLostException.Reason.REVOKED);
        var target = new Target() {
            @Override public Reply execute(String key) { calls.incrementAndGet(); throw refusal; }
        };
        assertSame(refusal, assertThrows(AuthorityLostException.class, () -> effects.execute(command, target)));
        assertEquals(EffectState.INTENDED, ledger.find(command.key()).orElseThrow().state());
        assertEquals(1, ledger.manualQueue(command.key().threadId(), 10).size());
        assertEquals(1, target.calls.get());
    }

    @Test
    void lostAuthorityDuringLookupCannotTriggerReplayEvenWithinRetentionWindow() {
        var command = JdbcEffectLedgerTest.command("authority-lost-lookup");
        var refusal = new AuthorityLostException(AuthorityLostException.Reason.REVOKED);
        var target = new Target() {
            @Override public Reply lookup(String key) { throw refusal; }
        };
        ledger.prepare(command, target.replayWindow(), Instant.now());
        ledger.markAttempted(command.key());
        assertSame(refusal, assertThrows(AuthorityLostException.class, () -> effects.execute(command, target)));
        assertEquals(EffectState.INTENDED, ledger.find(command.key()).orElseThrow().state());
        assertEquals(1, ledger.manualQueue(command.key().threadId(), 10).size());
        assertEquals(0, target.calls.get());
    }

    @Test
    void nonIdempotentOrExpiredUnknownOutcomeNeverGetsBlindlyResent() {
        for (Duration window : new Duration[]{Duration.ZERO, Duration.ofSeconds(1)}) {
            EffectCommand command = JdbcEffectLedgerTest.command("unsafe"); Target target = new Target();
            target.window = window; target.loseAck = true;
            assertEquals(EffectState.INTENDED, effects.execute(command, target).state());
            DurableEffects later = new DurableEffects(ledger, Clock.offset(Clock.systemUTC(), Duration.ofHours(1)));
            assertEquals(EffectState.INTENDED, later.execute(command, target).state());
            assertEquals(1, target.calls.get()); assertEquals(1, ledger.manualQueue(command.key().threadId(), 10).size());
        }
    }

    @Test
    void recoveryDoesNotExtendOriginalRetentionWhenApplicationClocksDisagree() throws Exception {
        EffectCommand command = JdbcEffectLedgerTest.command("replay-clock-skew");
        Target target = new Target();
        Instant creatorClock = Instant.now().plus(Duration.ofHours(1));
        ledger.prepare(command, Duration.ofMillis(150), creatorClock);
        ledger.markAttempted(command.key());
        target.window = Duration.ofHours(2); // A later, wider guarantee cannot extend the original one.
        Thread.sleep(400);
        Clock slow = Clock.offset(Clock.systemUTC(), Duration.ofHours(-1));

        Effect result = new DurableEffects(ledger, slow).execute(command, target);

        assertEquals(EffectState.INTENDED, result.state());
        assertEquals(0, target.calls.get(), "expired retention must not permit a second effect");
        assertEquals(1, ledger.manualQueue(command.key().threadId(), 10).size());
    }

    @Test
    void replayIsWithheldWhenRetentionExpiresDuringDispatchTelemetry() {
        for (int scenario = 0; scenario < 3; scenario++) {
            boolean persistedLimit = scenario == 0;
            boolean withdrawn = scenario == 2;
            EffectCommand command = JdbcEffectLedgerTest.command("replay-telemetry-expiry");
            Instant created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Duration retained = persistedLimit ? Duration.ofMinutes(1) : Duration.ofHours(1);
            Target target = new Target();
            target.window = persistedLimit ? Duration.ofHours(1) : Duration.ofMinutes(1);
            var prepared = ledger.prepare(command, retained, created);
            Instant boundary = persistedLimit ? prepared.replayUntil() : prepared.createdAt().plusSeconds(60);
            ledger.markAttempted(command.key());
            var current = new java.util.concurrent.atomic.AtomicReference<>(created);
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return current.get(); }
            };
            var telemetry = new EffectTelemetry(io.opentelemetry.api.OpenTelemetry.noop()) {
                @Override public EffectTarget.Reply target(Operation operation,
                        java.util.function.Supplier<EffectTarget.Reply> work) {
                    if (operation == Operation.DISPATCH) {
                        if (withdrawn) target.window = Duration.ZERO;
                        else current.set(boundary);
                    }
                    return super.target(operation, work);
                }
            };

            Effect result = new DurableEffects(ledger, clock, telemetry).execute(command, target);

            assertEquals(EffectState.INTENDED, result.state());
            assertEquals(0, target.calls.get(), "replay must not begin after expiry or withdrawal");
            assertEquals(1, ledger.manualQueue(command.key().threadId(), 10).size());
        }
    }

    @Test
    void compensationReplayIsWithheldWhenRetentionExpiresDuringDispatchTelemetry() {
        EffectCommand original = JdbcEffectLedgerTest.command("replay-compensate-original");
        Target target = new Target();
        assertEquals(EffectState.CONFIRMED, effects.execute(original, target).state());
        EffectCommand undo = EffectCommand.of(original.key().threadId(), "undo", "1", target.target(), "refund=10");
        Instant created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        target.window = Duration.ofMinutes(1);
        var prepared = ledger.prepareCompensation(original.key(), undo, target.window, created);
        ledger.markAttempted(undo.key());
        var current = new java.util.concurrent.atomic.AtomicReference<>(created);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return current.get(); }
        };
        var telemetry = new EffectTelemetry(io.opentelemetry.api.OpenTelemetry.noop()) {
            @Override public EffectTarget.Reply target(Operation operation,
                    java.util.function.Supplier<EffectTarget.Reply> work) {
                if (operation == Operation.DISPATCH) current.set(prepared.replayUntil());
                return super.target(operation, work);
            }
        };

        Effect result = new DurableEffects(ledger, clock, telemetry).compensate(original.key(), undo, target);

        assertEquals(EffectState.CONFIRMED, result.state(), "unknown compensation must not mark original undone");
        assertEquals(EffectState.INTENDED, ledger.find(undo.key()).orElseThrow().state());
        assertEquals(1, target.calls.get(), "only the original effect was sent");
        assertFalse(ledger.manualQueue(original.key().threadId(), 10).isEmpty());
    }

    @Test
    void initialPersistenceFailurePreventsAllTargetCalls() {
        DataSource broken = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> { throw new SQLException("offline"); });
        Target target = new Target();
        assertThrows(StoreUnavailableException.class, () -> new DurableEffects(new JdbcEffectLedger(broken))
                .execute(JdbcEffectLedgerTest.command("db-offline"), target));
        assertEquals(0, target.calls.get());
    }

    @Test
    void intentWriteFailureAfterClaimAlsoPreventsDispatchAndReleasesClaim() {
        JdbcEffectLedger failing = new JdbcEffectLedger(dataSource) {
            @Override public Prepared prepare(EffectCommand command, Duration window, Instant now) {
                throw new StoreUnavailableException("intent write failed", new SQLException());
            }
        };
        EffectCommand command = JdbcEffectLedgerTest.command("intent-failure"); Target target = new Target();
        assertThrows(StoreUnavailableException.class, () -> new DurableEffects(failing).execute(command, target));
        assertEquals(0, target.calls.get());
        assertEquals(EffectState.CONFIRMED, effects.execute(command, target).state());
    }

    @Test
    void outcomeWriteFailureLeavesACommittedIntentAndRecoveryUsesSameKey() {
        EffectCommand command = JdbcEffectLedgerTest.command("outcome-db"); Target target = new Target();
        JdbcEffectLedger failing = new JdbcEffectLedger(dataSource) {
            @Override public Effect recordOutcome(EffectKey key, EffectState state, String detail) {
                throw new StoreUnavailableException("outcome write failed", new SQLException());
            }
        };
        assertThrows(StoreUnavailableException.class, () -> new DurableEffects(failing).execute(command, target));
        assertEquals(EffectState.INTENDED, ledger.find(command.key()).orElseThrow().state());
        assertEquals(EffectState.CONFIRMED, effects.execute(command, target).state());
        assertEquals(1, target.applied.size());
    }

    @Test
    void competingWorkersDoNotDispatchSimultaneously() throws Exception {
        EffectCommand command = JdbcEffectLedgerTest.command("two-workers");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Target target = new Target() {
            public Reply execute(String key) throws Exception {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test target timed out");
                return super.execute(key);
            }
        };
        try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
            Future<Effect> first = workers.submit(() -> effects.execute(command, target));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(JdbcEffectLedger.EffectBusyException.class,
                        () -> new DurableEffects(new JdbcEffectLedger(dataSource)).execute(command, target));
            } finally { release.countDown(); }
            assertEquals(EffectState.CONFIRMED, first.get(10, TimeUnit.SECONDS).state());
            assertEquals(1, target.calls.get());
        }
    }

    @Test
    void failedWriteOfWithheldOutcomeLeavesIntentUnresolvedAndDoesNotResend() {
        var command=JdbcEffectLedgerTest.command("withheld-write-failure");var calls=new AtomicInteger();
        EffectTarget target=new EffectTarget() {
            public String target(){return command.target();}
            public Reply execute(String key){calls.incrementAndGet();throw new EffectTarget.ObservedOutcomeException(
                    new Reply(Outcome.CONFIRMED,"target confirmed"),new IllegalStateException("authority lost"));}
        };
        var failing=new JdbcEffectLedger(dataSource) {
            @Override public Effect recordOutcome(EffectKey key,EffectState state,String detail) {
                throw new StoreUnavailableException("outcome write failed",new SQLException());
            }
        };
        assertThrows(StoreUnavailableException.class,()->new DurableEffects(failing).execute(command,target));
        assertEquals(EffectState.INTENDED,ledger.find(command.key()).orElseThrow().state());
        assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
        assertEquals(1,calls.get(),"unknown retained intent must not be resent without a replay guarantee");
        assertEquals(1,ledger.manualQueue(command.key().threadId(),10).size());
    }

    @Test
    void withheldAuthoritativeLookupIsRetainedBeforeRefusal() {
        EffectCommand command=JdbcEffectLedgerTest.command("withheld-lookup");
        ledger.prepare(command,Duration.ZERO,Instant.now());ledger.markAttempted(command.key());
        var refusal=new IllegalStateException("authority lost");var sent=new AtomicInteger();
        EffectTarget target=new EffectTarget() {
            public String target(){return command.target();}
            public Reply execute(String key){sent.incrementAndGet();return new Reply(Outcome.CONFIRMED,"unexpected");}
            public Reply lookup(String key){throw new EffectTarget.ObservedOutcomeException(new Reply(Outcome.CONFIRMED,"target confirmed"),refusal);}
        };
        assertSame(refusal,assertThrows(IllegalStateException.class,()->effects.execute(command,target)));
        assertEquals(EffectState.CONFIRMED,ledger.find(command.key()).orElseThrow().state());
        assertEquals(0,sent.get());
    }

    @Test
    void withheldConfirmedCompensationIsRetainedBeforeRefusalAndNeverResent() {
        EffectCommand original=JdbcEffectLedgerTest.command("withheld-original");var originalTarget=new Target();
        assertEquals(EffectState.CONFIRMED,effects.execute(original,originalTarget).state());
        EffectCommand undo=EffectCommand.of(original.key().threadId(),"refund","withheld",originalTarget.target(),"refund=10");
        var sent=new AtomicInteger();var refusal=new IllegalStateException("authority lost");
        EffectTarget target=new EffectTarget() {
            public String target(){return originalTarget.target();}
            public Reply execute(String key) {
                sent.incrementAndGet();
                throw new EffectTarget.ObservedOutcomeException(new Reply(Outcome.CONFIRMED,"target confirmed"),refusal);
            }
        };
        assertSame(refusal,assertThrows(IllegalStateException.class,()->effects.compensate(original.key(),undo,target)));
        assertEquals(EffectState.CONFIRMED,ledger.find(undo.key()).orElseThrow().state());
        assertEquals(EffectState.CONFIRMED,ledger.find(original.key()).orElseThrow().state());
        assertEquals(EffectState.COMPENSATED,effects.compensate(original.key(),undo,target).state());
        assertEquals(1,sent.get(),"confirmed compensation must never be sent twice");
    }

    @Test
    void compensationUsesSeparateIntentAndFailedCompensationStaysVisible() {
        EffectCommand original = JdbcEffectLedgerTest.command("pay"); Target target = new Target();
        effects.execute(original, target);
        EffectCommand undo = EffectCommand.of(original.key().threadId(), "refund", "1", target.target(), "refund=10");
        target.loseAck = true;
        assertEquals(EffectState.CONFIRMED, effects.compensate(original.key(), undo, target).state());
        assertFalse(ledger.manualQueue(original.key().threadId(), 10).isEmpty());
        assertEquals(EffectState.COMPENSATED, effects.compensate(original.key(), undo, target).state());
        assertEquals(2, target.applied.size());
        assertTrue(ledger.manualQueue(original.key().threadId(), 10).isEmpty());
        assertEquals(EffectState.COMPENSATED, effects.compensate(original.key(), undo, target).state());

        EffectCommand other = JdbcEffectLedgerTest.command("other"); Target rejectedUndo = new Target();
        effects.execute(other, rejectedUndo); rejectedUndo.refuse = true;
        EffectCommand refused = EffectCommand.of(other.key().threadId(), "undo", "1", target.target(), "refund=10");
        assertEquals(EffectState.CONFIRMED, effects.compensate(other.key(), refused, rejectedUndo).state());
        assertEquals(EffectState.FAILED, ledger.find(refused.key()).orElseThrow().state());
        assertEquals(1, ledger.manualQueue(other.key().threadId(), 10).size());
    }

    @Test
    void effectClaimsDoNotShareTheAuditAndLifecycleBigintNamespace() throws Exception {
        EffectCommand command = JdbcEffectLedgerTest.command("lock-namespace");
        byte[] digest = java.util.HexFormat.of().parseHex(
                io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder.sha256Hex(
                new io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder("oa.effect.lock.v1")
                        .text(command.key().threadId().value()).text(command.key().nodeId())
                        .text(command.key().idempotencyKey()).toBytes()));
        long previousKey = java.nio.ByteBuffer.wrap(digest).getLong();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                statement.setLong(1, previousKey); statement.execute();
            }
            try {
                assertEquals(EffectState.CONFIRMED, effects.execute(command, new Target()).state());
            } finally { connection.rollback(); }
        }
    }

    @Test
    void anotherCompensationKeyCannotLeaveAnOrphanIntentOrDispatch() {
        EffectCommand original = JdbcEffectLedgerTest.command("orphan-regression"); Target target = new Target();
        effects.execute(original, target);
        EffectCommand undo = EffectCommand.of(original.key().threadId(), "refund", "1", target.target(), "refund=10");
        assertEquals(EffectState.COMPENSATED, effects.compensate(original.key(), undo, target).state());
        var unresolved = ledger.findUnresolved(original.key().threadId());
        var queue = ledger.manualQueue(original.key().threadId(), 10);
        int calls = target.calls.get();
        EffectCommand other = EffectCommand.of(original.key().threadId(), "refund-again", "1", target.target(), "refund=10");
        assertThrows(IllegalStateException.class, () -> effects.compensate(original.key(), other, target));
        assertTrue(ledger.find(other.key()).isEmpty(), "intent must roll back when its link is refused");
        assertEquals(unresolved, ledger.findUnresolved(original.key().threadId()));
        assertEquals(queue, ledger.manualQueue(original.key().threadId(), 10));
        assertEquals(calls, target.calls.get());
    }

    @Test
    void unrelatedConfirmedEffectCannotBeReusedAsProofOfCompensation() {
        EffectCommand original = JdbcEffectLedgerTest.command("original"); Target target = new Target();
        effects.execute(original, target);
        EffectCommand unrelated = EffectCommand.of(original.key().threadId(), "another-charge", "1",
                target.target(), "amount=10");
        effects.execute(unrelated, target);
        assertThrows(IllegalStateException.class, () -> effects.compensate(original.key(), unrelated, target));
        assertEquals(EffectState.CONFIRMED, ledger.find(original.key()).orElseThrow().state());
        EffectCommand undo = EffectCommand.of(original.key().threadId(), "refund", "1", target.target(), "refund=10");
        effects.compensate(original.key(), undo, target);
        assertThrows(IllegalStateException.class, () -> effects.execute(undo, target));
    }

    @Test
    void interruptionAfterRealIntentCommitLeavesUnattemptedIntentAndReleasesClaim() {
        EffectCommand command=JdbcEffectLedgerTest.command("cancel-after-intent");Target target=new Target();
        var interrupting=new JdbcEffectLedger(dataSource) {
            public Prepared prepare(EffectCommand c,Duration window,Instant now) {
                Prepared stored=super.prepare(c,window,now);Thread.currentThread().interrupt();return stored;
            }
        };
        try {
            assertThrows(CancellationException.class,()->new DurableEffects(interrupting).execute(command,target));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,target.calls.get());
        } finally {Thread.interrupted();}
        assertFalse(ledger.prepare(command,target.replayWindow(),Instant.now()).attempted());
        // A fresh physical connection cannot reenter a leaked advisory claim from Agroal's existing session.
        assertEquals(EffectState.CONFIRMED,new DurableEffects(new JdbcEffectLedger(EffectCrashWorker.dataSource())).execute(command,target).state());
        assertEquals(Set.of(command.key().idempotencyKey()),target.applied);assertEquals(1,target.calls.get());
    }

    @Test
    void interruptionAfterRealAttemptCommitNeverDispatchesAndFreshConnectionReconcilesSameKey() {
        EffectCommand command=JdbcEffectLedgerTest.command("cancel-after-attempt");Target target=new Target();
        var interrupting=new JdbcEffectLedger(dataSource) {
            public void markAttempted(EffectKey key){super.markAttempted(key);Thread.currentThread().interrupt();}
        };
        try {
            assertThrows(CancellationException.class,()->new DurableEffects(interrupting).execute(command,target));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,target.calls.get());
        } finally {Thread.interrupted();}
        assertTrue(ledger.prepare(command,target.replayWindow(),Instant.now()).attempted());
        assertEquals(EffectState.INTENDED,ledger.find(command.key()).orElseThrow().state());
        assertEquals(EffectState.CONFIRMED,new DurableEffects(new JdbcEffectLedger(EffectCrashWorker.dataSource())).execute(command,target).state());
        assertEquals(Set.of(command.key().idempotencyKey()),target.applied);assertEquals(1,target.calls.get());
    }

    @Test
    void interruptionDuringLookupCannotResendAndLaterFreshOwnerUsesTheRetainedReceipt() {
        EffectCommand command=JdbcEffectLedgerTest.command("cancel-lookup");
        var interruptLookup=new java.util.concurrent.atomic.AtomicBoolean(true);
        Target target=new Target() {
            public Reply lookup(String key) {
                if(interruptLookup.getAndSet(false)){Thread.currentThread().interrupt();return new Reply(Outcome.UNKNOWN,null);}
                return new Reply(applied.contains(key)?Outcome.CONFIRMED:Outcome.UNKNOWN,"lookup");
            }
        };
        target.loseAck=true;
        assertEquals(EffectState.INTENDED,effects.execute(command,target).state());
        try {
            assertThrows(CancellationException.class,()->effects.execute(command,target));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,target.calls.get());
        } finally {Thread.interrupted();}
        assertEquals(EffectState.CONFIRMED,new DurableEffects(new JdbcEffectLedger(EffectCrashWorker.dataSource())).execute(command,target).state());
        assertEquals(1,target.calls.get());assertTrue(ledger.manualQueue(command.key().threadId(),10).isEmpty());
    }

    @Test
    void interruptionAfterCompensationIntentRetainsLinkAndFreshOwnerRefundsOnce() {
        EffectCommand original=JdbcEffectLedgerTest.command("cancel-refund");Target charge=new Target();effects.execute(original,charge);
        Target refund=new Target();EffectCommand undo=EffectCommand.of(original.key().threadId(),"refund","1",refund.target(),"refund=10");
        var interrupting=new JdbcEffectLedger(dataSource) {
            public Prepared prepareCompensation(EffectKey key,EffectCommand command,Duration window,Instant now) {
                Prepared stored=super.prepareCompensation(key,command,window,now);Thread.currentThread().interrupt();return stored;
            }
        };
        try {
            assertThrows(CancellationException.class,()->new DurableEffects(interrupting).compensate(original.key(),undo,refund));
            assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,refund.calls.get());
        } finally {Thread.interrupted();}
        assertEquals(EffectState.CONFIRMED,ledger.find(original.key()).orElseThrow().state());
        assertEquals(EffectState.INTENDED,ledger.find(undo.key()).orElseThrow().state());
        assertEquals(EffectState.COMPENSATED,new DurableEffects(new JdbcEffectLedger(EffectCrashWorker.dataSource())).compensate(original.key(),undo,refund).state());
        assertEquals(Set.of(undo.key().idempotencyKey()),refund.applied);assertEquals(1,refund.calls.get());
    }
}
