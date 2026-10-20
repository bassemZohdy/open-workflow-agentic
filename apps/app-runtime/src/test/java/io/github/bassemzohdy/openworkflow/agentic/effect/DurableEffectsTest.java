package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.DurableEffects;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
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
}
