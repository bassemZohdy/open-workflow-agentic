package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.JdbcEffectLedger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JdbcEffectLedgerTest {
    @Inject JdbcEffectLedger ledger;
    @Inject DataSource dataSource;

    static EffectCommand command(String node) {
        return EffectCommand.of(ThreadId.derive("effect-test", "alice", UUID.randomUUID().toString()),
                node, "1", "test:payments", "amount=10");
    }

    @Test
    void persistedIntentIsIdempotentAndItsStateMachineCannotBeReversed() {
        EffectCommand command = command("pay");
        Effect first = ledger.prepare(command, Duration.ofHours(1), Instant.now()).effect();
        JdbcEffectLedger fresh = new JdbcEffectLedger(dataSource);
        assertEquals(first, fresh.prepare(command, Duration.ofHours(1), Instant.now()).effect());
        assertEquals(List.of(first), fresh.findUnresolved(command.key().threadId()));
        Effect done = fresh.recordOutcome(command.key(), EffectState.CONFIRMED, "confirmed");
        assertEquals(done, ledger.recordOutcome(command.key(), EffectState.CONFIRMED, "duplicate"));
        assertThrows(IllegalStateException.class, () -> ledger.recordOutcome(command.key(), EffectState.FAILED, null));
        assertTrue(ledger.findUnresolved(command.key().threadId()).isEmpty());
        assertEquals(EffectState.COMPENSATED, ledger.recordOutcome(command.key(), EffectState.COMPENSATED, "undo").state());
        assertThrows(IllegalStateException.class, () -> ledger.recordOutcome(command.key(), EffectState.CONFIRMED, null));
    }

    @Test
    void conflictingTargetOrRequestAndMissingIntentAreRefused() {
        EffectCommand command = command("conflict");
        ledger.prepare(command, Duration.ZERO, Instant.now());
        assertThrows(IllegalStateException.class, () -> ledger.prepare(new EffectCommand(command.key(), "other",
                command.requestFingerprint()), Duration.ZERO, Instant.now()));
        assertThrows(IllegalStateException.class, () -> ledger.prepare(new EffectCommand(command.key(), command.target(),
                "a".repeat(64)), Duration.ZERO, Instant.now()));
        assertThrows(NoSuchElementException.class, () -> ledger.recordOutcome(command("missing").key(), EffectState.FAILED, null));
        assertThrows(IllegalArgumentException.class, () -> ledger.recordIntent(new Effect(command.key().threadId(),
                "other", "key", command.target(), EffectState.CONFIRMED, null, Instant.now())));
    }

    @Test
    void concurrentInsertsUseTheCommittedWinnerAndOutcomesSerialize() throws Exception {
        EffectCommand command = command("race");
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Effect> insert = () -> { start.await(); return new JdbcEffectLedger(dataSource)
                    .prepare(command, Duration.ZERO, Instant.now()).effect(); };
            Future<Effect> a = workers.submit(insert), b = workers.submit(insert); start.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            Callable<Boolean> outcome = () -> {
                try { ledger.recordOutcome(command.key(), EffectState.CONFIRMED, "ok"); return true; }
                catch (IllegalStateException e) { return false; }
            };
            assertTrue(workers.submit(outcome).get());
            assertThrows(IllegalStateException.class, () -> ledger.recordOutcome(command.key(), EffectState.FAILED, null));
        }
    }

    @Test
    void manualQueueSurvivesNewStoreAndIsBoundedAndThreadScoped() {
        EffectCommand command = command("manual");
        ledger.prepare(command, Duration.ZERO, Instant.now());
        ledger.requireManualResolution(command.key(), "effect.outcome.unknown");
        JdbcEffectLedger fresh = new JdbcEffectLedger(dataSource);
        assertEquals(command.key(), fresh.manualQueue(command.key().threadId(), 1).getFirst().key());
        assertTrue(fresh.manualQueue(command("other").key().threadId(), 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fresh.manualQueue(command.key().threadId(), 201));
        fresh.recordOutcome(command.key(), EffectState.CONFIRMED, "authoritative lookup");
        assertTrue(ledger.manualQueue(command.key().threadId(), 10).isEmpty());
    }
}
