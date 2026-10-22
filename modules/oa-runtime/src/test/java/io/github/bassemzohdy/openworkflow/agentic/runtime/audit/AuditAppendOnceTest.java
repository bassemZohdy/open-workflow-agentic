package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditDraftFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditReceipt;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditSource;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditSource.Transition;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CyclicBarrier;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The central append-once consumer against real PostgreSQL in a private database that carries GPT's PROPOSED V75
 * (pinned copy; see {@link AuditSourceTestDatabase}). Proves append-once, exact duplicates, conflicts, rollback of every
 * partial write, lost commit replies, corruption and missing schema, same-source contention across chains, retained
 * references and least privilege. It proves nothing about activation, roles in a deployment or producers.
 */
class AuditAppendOnceTest {

    static final Subject ACTOR = new Subject(SubjectKind.SERVICE, "effect-producer");
    static final Instant AT = Instant.parse("2026-10-21T08:00:00.123456Z");
    static final String ROLE = "oa_src_writer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    static final String PASSWORD = "writer-test-only";

    static AuditSourceTestDatabase db;
    static AuditAppendOnce once;

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = AuditSourceTestDatabase.withProposedV75();
        once = new AuditAppendOnce(db.dataSource());
        db.execute("create role " + ROLE + " login password '" + PASSWORD + "'",
                "grant usage on schema audit to " + ROLE,
                "grant select, insert on audit.event, audit.head_log, audit.source_binding to " + ROLE);
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        db.execute("revoke all on all tables in schema audit from " + ROLE,
                "revoke usage on schema audit from " + ROLE, "drop role if exists " + ROLE);
        db.close();
    }

    // ------------------------------------------------------------------ builders

    static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String newChain() {
        return "c" + UlidGenerator.shared().next().value();
    }

    static Ulid newId() {
        return UlidGenerator.shared().next();
    }

    static AuditEvent draft(String chain, Ulid id, String resourceId) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(AuditKeys.CHAIN, chain);
        attributes.put("effect", resourceId);
        return AuditDraft.of(id, AT, ACTOR, "effect.dispatch", "effect", resourceId, attributes);
    }

    static AuditSource source(String scope, String key, Transition transition, long sequence, AuditEvent draft) {
        return new AuditSource(1, sha(scope), sha(key), transition, sequence, AuditDraftFingerprint.of(draft));
    }

    record Fixture(String chain, AuditEvent draft, AuditSource source) {
    }

    static Fixture fixture() {
        String chain = newChain();
        AuditEvent draft = draft(chain, newId(), "effect-" + chain);
        return new Fixture(chain, draft, source("scope-" + chain, "key-" + chain, Transition.INTENT, 1, draft));
    }

    static long count(String sql, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static long events(String chain) throws SQLException {
        return count("select count(*) from audit.event where chain_id = ?", chain);
    }

    static long heads(String chain) throws SQLException {
        return count("select count(*) from audit.head_log where chain_id = ?", chain);
    }

    static long bindings(String chain) throws SQLException {
        return count("select count(*) from audit.source_binding where chain_id = ?", chain);
    }

    static List<AuditEvent> chain(String chain) throws SQLException {
        List<AuditEvent> events = new ArrayList<>();
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement s = c.prepareStatement("select " + AuditRows.EVENT_COLUMNS
                     + " from audit.event where chain_id = ? order by seq")) {
            s.setString(1, chain);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    events.add(AuditRows.event(rs));
                }
            }
        }
        return events;
    }

    static void assertNothingStored(String chain) throws SQLException {
        assertEquals(0, events(chain), "event");
        assertEquals(0, heads(chain), "head");
        assertEquals(0, bindings(chain), "binding");
    }

    // ------------------------------------------------------------------ new source and exact duplicates

    @Test
    void aNewSourceStoresEventHeadAndBindingTogetherAndReturnsAMatchingReceipt() throws SQLException {
        Fixture f = fixture();

        AuditReceipt receipt = once.appendOnce(f.source, f.draft);

        assertEquals(f.draft.id(), receipt.eventId());
        assertEquals(f.chain, receipt.chainId());
        assertEquals(1, receipt.chainSequence());
        assertEquals(f.source.contentFingerprint(), receipt.contentFingerprint());
        receipt.requireMatches(f.source, f.draft);
        assertEquals(1, events(f.chain));
        assertEquals(1, heads(f.chain));
        assertEquals(1, bindings(f.chain));
        List<AuditEvent> stored = chain(f.chain);
        assertEquals(receipt.hash(), stored.get(0).hash());
        assertTrue(AuditHash.verifyChain(stored).valid());
        assertEquals(AuditDraftFingerprint.of(stored.get(0)), f.source.contentFingerprint(), "stored content is the draft");
    }

    @Test
    void anExactDuplicateReturnsTheOriginalReceiptWithoutANewEventTimeIdSequenceOrHead() throws SQLException {
        Fixture f = fixture();
        AuditReceipt first = once.appendOnce(f.source, f.draft);
        AuditEvent storedBefore = chain(f.chain).get(0);

        AuditReceipt again = once.appendOnce(f.source, draft(f.chain, f.draft.id(), "effect-" + f.chain));

        assertEquals(first, again);
        assertEquals(1, events(f.chain));
        assertEquals(1, heads(f.chain));
        assertEquals(1, bindings(f.chain));
        assertEquals(storedBefore, chain(f.chain).get(0));
    }

    @Test
    void sixteenConcurrentIdenticalCallsStoreOneEventAndEveryCallerGetsTheSameReceipt() throws Exception {
        Fixture f = fixture();
        List<Callable<AuditReceipt>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            calls.add(() -> once.appendOnce(f.source, f.draft));
        }
        List<AuditReceipt> receipts = runTogether(calls);

        assertEquals(1, receipts.stream().distinct().count());
        assertEquals(1, events(f.chain));
        assertEquals(1, heads(f.chain));
        assertEquals(1, bindings(f.chain));
    }

    @Test
    void ordinaryAppendsAndAppendOnceOnOneChainStayGapFreeAndLinked() throws Exception {
        String chain = newChain();
        AuditTrail trail = new AuditTrail(db.dataSource());
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int n = i;
            if (n % 2 == 0) {
                calls.add(() -> trail.append(draft(chain, newId(), "plain-" + n)));
            } else {
                AuditEvent d = draft(chain, newId(), "once-" + n);
                AuditSource s = source("scope-" + n + chain, "key-" + n + chain, Transition.OUTCOME, n, d);
                calls.add(() -> once.appendOnce(s, d));
            }
        }
        runTogether(calls);

        List<AuditEvent> stored = chain(chain);
        assertEquals(12, stored.size());
        for (int i = 0; i < stored.size(); i++) {
            assertEquals(i + 1, stored.get(i).sequence());
        }
        assertTrue(AuditHash.verifyChain(stored).valid());
        assertEquals(6, bindings(chain));
    }

    @Test
    void differentTransitionsAndSequencesOfOneKeyAreDistinctSources() throws SQLException {
        String chain = newChain();
        List<AuditReceipt> receipts = new ArrayList<>();
        for (Transition t : List.of(Transition.INTENT, Transition.DISPATCH, Transition.OUTCOME)) {
            AuditEvent d = draft(chain, newId(), "effect-" + t);
            receipts.add(once.appendOnce(source("scope-" + chain, "key-" + chain, t, 7, d), d));
        }
        AuditEvent d = draft(chain, newId(), "effect-next");
        receipts.add(once.appendOnce(source("scope-" + chain, "key-" + chain, Transition.INTENT, 8, d), d));

        assertEquals(4, receipts.stream().map(AuditReceipt::eventId).distinct().count());
        assertEquals(List.of(1L, 2L, 3L, 4L), receipts.stream().map(AuditReceipt::chainSequence).toList());
        assertEquals(4, bindings(chain));
    }

    // ------------------------------------------------------------------ conflicts

    @Test
    void theSameSourceWithChangedContentIdActorOrChainIsAConflictAndAppendsNothing() throws SQLException {
        Fixture f = fixture();
        once.appendOnce(f.source, f.draft);

        // changed content (resource id), same source key
        AuditEvent changed = draft(f.chain, f.draft.id(), "other-resource");
        AuditSource changedSource = new AuditSource(1, f.source.scopeFingerprint(), f.source.keyFingerprint(),
                f.source.transition(), f.source.sequence(), AuditDraftFingerprint.of(changed));
        assertEquals(f.source.key(), changedSource.key());
        assertThrows(AuditSourceConflictException.class, () -> once.appendOnce(changedSource, changed));

        // another event id
        AuditEvent otherId = draft(f.chain, newId(), "effect-" + f.chain);
        AuditSource otherIdSource = new AuditSource(1, f.source.scopeFingerprint(), f.source.keyFingerprint(),
                f.source.transition(), f.source.sequence(), AuditDraftFingerprint.of(otherId));
        assertThrows(AuditSourceConflictException.class, () -> once.appendOnce(otherIdSource, otherId));

        // another chain
        String otherChain = newChain();
        AuditEvent moved = draft(otherChain, f.draft.id(), "effect-" + f.chain);
        AuditSource movedSource = new AuditSource(1, f.source.scopeFingerprint(), f.source.keyFingerprint(),
                f.source.transition(), f.source.sequence(), AuditDraftFingerprint.of(moved));
        assertThrows(AuditSourceConflictException.class, () -> once.appendOnce(movedSource, moved));

        assertEquals(1, events(f.chain));
        assertNothingStored(otherChain);
        assertEquals(1, bindings(f.chain));
    }

    @Test
    void anEventIdAlreadyUsedByAnotherSourceOrByAnOrdinaryAppendIsAConflict() throws SQLException {
        Fixture f = fixture();
        once.appendOnce(f.source, f.draft);
        String chain2 = newChain();
        AuditEvent reusedId = draft(chain2, f.draft.id(), "reuse");
        AuditSource other = source("scope-b" + chain2, "key-b" + chain2, Transition.INTENT, 1, reusedId);
        assertThrows(AuditSourceConflictException.class, () -> once.appendOnce(other, reusedId));
        assertNothingStored(chain2);

        String chain3 = newChain();
        AuditEvent plain = draft(chain3, newId(), "plain");
        new AuditTrail(db.dataSource()).append(plain);
        AuditEvent same = draft(newChain(), plain.id(), "plain-again");
        AuditSource third = source("scope-c" + chain3, "key-c" + chain3, Transition.INTENT, 1, same);
        assertThrows(AuditSourceConflictException.class, () -> once.appendOnce(third, same));
        assertEquals(1, events(chain3));
        assertEquals(0, bindings(chain3));
    }

    @Test
    void sameSourceContendedAcrossChainsHasExactlyOneWinnerAndNothingOnTheLosersChains() throws Exception {
        for (int round = 0; round < 6; round++) {
            String scope = "scope-race-" + UUID.randomUUID();
            String key = "key-race-" + UUID.randomUUID();
            List<String> chains = List.of(newChain(), newChain(), newChain(), newChain());
            List<Callable<Object>> calls = new ArrayList<>();
            for (String chain : chains) {
                AuditEvent d = draft(chain, newId(), "race");
                AuditSource s = source(scope, key, Transition.DISPATCH, 5, d);
                calls.add(() -> {
                    try {
                        return once.appendOnce(s, d);
                    } catch (AuditSourceConflictException conflict) {
                        return conflict;
                    }
                });
            }
            List<Object> results = runTogether(calls);

            assertEquals(1, results.stream().filter(r -> r instanceof AuditReceipt).count(), "one winner");
            assertEquals(3, results.stream().filter(r -> r instanceof AuditSourceConflictException).count());
            long stored = 0;
            for (String chain : chains) {
                stored += events(chain);
                assertEquals(events(chain), bindings(chain));
                assertEquals(events(chain), heads(chain));
            }
            assertEquals(1, stored, "exactly one event for the one source");
        }
    }

    @Test
    void aSourceWhoseContentIsNotTheDraftsFingerprintOrWhoseTimeIsFinerThanAMicrosecondAppendsNothing() throws SQLException {
        Fixture f = fixture();
        AuditSource wrong = new AuditSource(1, f.source.scopeFingerprint(), f.source.keyFingerprint(),
                f.source.transition(), f.source.sequence(), sha("not the draft"));
        assertThrows(IllegalArgumentException.class, () -> once.appendOnce(wrong, f.draft));

        AuditEvent nanos = AuditDraft.of(newId(), Instant.parse("2026-10-21T08:00:00.123456789Z"), ACTOR, "a", "r", "1",
                Map.of(AuditKeys.CHAIN, f.chain));
        AuditSource nanosSource = new AuditSource(1, sha("s"), sha("k"), Transition.INTENT, 1, sha("x"));
        assertThrows(IllegalArgumentException.class, () -> once.appendOnce(nanosSource, nanos));
        assertNothingStored(f.chain);
    }

    // ------------------------------------------------------------------ rollback of every partial write

    @Test
    void aFailureAtAnyStepRollsBackTheEventTheHeadAndTheBinding() throws SQLException {
        // the binding insert fails after the event and the head were written
        Fixture late = fixture();
        db.execute("create or replace function public.fail_binding() returns trigger language plpgsql as $$ begin "
                + "if new.chain_id = '" + late.chain + "' then raise exception 'injected binding failure'; end if; "
                + "return new; end $$",
                "create trigger a_fail_binding before insert on audit.source_binding for each row execute function public.fail_binding()");
        try {
            AuditStoreException failure = assertThrows(AuditStoreException.class, () -> once.appendOnce(late.source, late.draft));
            assertFalse(failure instanceof AuditCommitUncertainException);
            assertNothingStored(late.chain);
        } finally {
            db.execute("drop trigger a_fail_binding on audit.source_binding");
        }

        // the head insert fails after the event was written
        Fixture mid = fixture();
        db.execute("create or replace function public.fail_head() returns trigger language plpgsql as $$ begin "
                + "if new.chain_id = '" + mid.chain + "' then raise exception 'injected head failure'; end if; "
                + "return new; end $$",
                "create trigger a_fail_head before insert on audit.head_log for each row execute function public.fail_head()");
        try {
            assertThrows(AuditStoreException.class, () -> once.appendOnce(mid.source, mid.draft));
            assertNothingStored(mid.chain);
        } finally {
            db.execute("drop trigger a_fail_head on audit.head_log");
        }

        // after each injected failure the same source appends exactly once
        once.appendOnce(late.source, late.draft);
        once.appendOnce(mid.source, mid.draft);
        assertEquals(1, bindings(late.chain));
        assertEquals(1, bindings(mid.chain));
    }

    // ------------------------------------------------------------------ lost commit replies

    @Test
    void aLostCommitReplyAfterARealCommitIsConfirmedByReadbackAndReturnsTheReceipt() throws SQLException {
        Fixture f = fixture();
        AuditAppendOnce flaky = new AuditAppendOnce(failingCommit(db.dataSource(), true, false));

        AuditReceipt receipt = flaky.appendOnce(f.source, f.draft);

        receipt.requireMatches(f.source, f.draft);
        assertEquals(1, events(f.chain));
        assertEquals(1, bindings(f.chain));
        assertEquals(receipt, once.appendOnce(f.source, f.draft));
        assertEquals(1, events(f.chain));
    }

    @Test
    void aFailedCommitThatDidNotHappenIsUncertainNotAbsenceAndTheSameSourceThenSucceedsOnce() throws SQLException {
        Fixture f = fixture();
        AuditAppendOnce flaky = new AuditAppendOnce(failingCommit(db.dataSource(), false, false));

        assertThrows(AuditCommitUncertainException.class, () -> flaky.appendOnce(f.source, f.draft));
        assertNothingStored(f.chain);

        AuditReceipt receipt = once.appendOnce(f.source, f.draft);
        assertEquals(1, events(f.chain));
        assertEquals(receipt, once.appendOnce(f.source, f.draft));
        assertEquals(1, events(f.chain));
    }

    @Test
    void aLostReplyWhoseReadbackAlsoFailsIsUncertainEvenThoughTheEventWasCommitted() throws SQLException {
        Fixture f = fixture();
        AuditAppendOnce flaky = new AuditAppendOnce(failingCommit(db.dataSource(), true, true));

        assertThrows(AuditCommitUncertainException.class, () -> flaky.appendOnce(f.source, f.draft));
        assertEquals(1, events(f.chain), "it was committed; the caller must not read the exception as absence");
        assertEquals(once.appendOnce(f.source, f.draft).eventId(), f.draft.id());
        assertEquals(1, events(f.chain));
    }

    @Test
    void aLostReplyIsConfirmedThroughAPoolOfOneBecauseTheFailedConnectionIsReleasedFirst() throws SQLException {
        Fixture f = fixture();
        AuditAppendOnce flaky = new AuditAppendOnce(oneConnectionAtATime(failingCommit(db.dataSource(), true, false)));

        AuditReceipt receipt = flaky.appendOnce(f.source, f.draft);

        receipt.requireMatches(f.source, f.draft);
        assertEquals(1, events(f.chain));
        assertEquals(1, bindings(f.chain));
        assertEquals(receipt, once.appendOnce(f.source, f.draft));
    }

    @Test
    void concurrentLostRepliesThroughASmallPoolAllConfirmWithoutSpareConnections() throws Exception {
        int calls = 4;
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            fixtures.add(fixture());
        }
        DataSource limited = limitedTo(failingEveryFirstCommit(db.dataSource()), calls);
        AuditAppendOnce flaky = new AuditAppendOnce(limited);
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            List<Future<AuditReceipt>> futures = new ArrayList<>();
            for (Fixture f : fixtures) {
                futures.add(pool.submit(() -> flaky.appendOnce(f.source, f.draft)));
            }
            for (int i = 0; i < calls; i++) {
                futures.get(i).get(60, TimeUnit.SECONDS).requireMatches(fixtures.get(i).source, fixtures.get(i).draft);
                assertEquals(1, events(fixtures.get(i).chain));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sourceLocksAndChainLocksUseSeparateKeyspacesSoCrossedNamesNeverDeadlock() throws Exception {
        // Source identity does not depend on content, so both source keys are known before the chains are chosen.
        AuditEvent probe = draft(newChain(), newId(), "probe");
        AuditSource keyA = source("scope-a-" + newChain(), "key-a", Transition.INTENT, 1, probe);
        AuditSource keyB = source("scope-b-" + newChain(), "key-b", Transition.INTENT, 1, probe);
        String chainA = "oa.audit.source:" + keyB.key();
        String chainB = "oa.audit.source:" + keyA.key();
        AuditEvent draftA = draft(chainA, newId(), "crossed-a");
        AuditEvent draftB = draft(chainB, newId(), "crossed-b");
        AuditSource sourceA = new AuditSource(1, keyA.scopeFingerprint(), keyA.keyFingerprint(), Transition.INTENT, 1,
                AuditDraftFingerprint.of(draftA));
        AuditSource sourceB = new AuditSource(1, keyB.scopeFingerprint(), keyB.keyFingerprint(), Transition.INTENT, 1,
                AuditDraftFingerprint.of(draftB));
        assertEquals(keyA.key(), sourceA.key());
        assertEquals(keyB.key(), sourceB.key());

        CyclicBarrier bothHoldSourceLocks = new CyclicBarrier(2);
        AuditAppendOnce synchronised = new AuditAppendOnce(afterSourceLock(db.dataSource(), bothHoldSourceLocks));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AuditReceipt> a = pool.submit(() -> synchronised.appendOnce(sourceA, draftA));
            Future<AuditReceipt> b = pool.submit(() -> synchronised.appendOnce(sourceB, draftB));
            a.get(60, TimeUnit.SECONDS).requireMatches(sourceA, draftA);
            b.get(60, TimeUnit.SECONDS).requireMatches(sourceB, draftB);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, events(chainA));
        assertEquals(1, events(chainB));
    }

    // ------------------------------------------------------------------ corruption, outage and missing schema

    @Test
    void corruptRetainedHistoryIsAStoreFailureNotAReceiptNotAConflictAndNotAbsence() throws SQLException {
        Fixture f = fixture();
        once.appendOnce(f.source, f.draft);
        tamper("update audit.event set resource_id = 'forged' where chain_id = '" + f.chain + "'");

        AuditStoreException failure = assertThrows(AuditStoreException.class, () -> once.appendOnce(f.source, f.draft));
        assertFalse(failure instanceof AuditCommitUncertainException);
        assertEquals(1, events(f.chain));

        Fixture g = fixture();
        once.appendOnce(g.source, g.draft);
        tamper("update audit.source_binding set hash = '" + "0".repeat(64) + "' where chain_id = '" + g.chain + "'");
        assertThrows(AuditStoreException.class, () -> once.appendOnce(g.source, g.draft));
    }

    @Test
    void aForgedEventResealedConsistentlyWithItsHeadAndBindingStillFailsTheContentCheck() throws SQLException {
        Fixture f = fixture();
        once.appendOnce(f.source, f.draft);
        AuditEvent stored = chain(f.chain).get(0);
        // other content, sealed with a valid hash, and the head and binding rewritten to match: every hash agrees
        AuditEvent forged = AuditHash.seal(new AuditEvent(stored.id(), stored.sequence(), stored.occurredAt(),
                stored.actor(), stored.action(), stored.resourceType(), "forged", stored.attributes(),
                stored.previousHash(), AuditHash.GENESIS));
        assertTrue(AuditHash.verifyChain(List.of(forged)).valid());
        tamper("update audit.event set resource_id = 'forged', hash = '" + forged.hash() + "' where chain_id = '" + f.chain + "'",
                "update audit.head_log set hash = '" + forged.hash() + "' where chain_id = '" + f.chain + "'",
                "update audit.source_binding set hash = '" + forged.hash() + "' where chain_id = '" + f.chain + "'");

        AuditStoreException failure = assertThrows(AuditStoreException.class, () -> once.appendOnce(f.source, f.draft));
        assertFalse(failure instanceof AuditCommitUncertainException);
    }

    @Test
    void aMissingBindingSchemaIsAStoreFailureAndAppendsNoEvent() throws SQLException {
        try (AuditSourceTestDatabase bare = AuditSourceTestDatabase.withoutProposedV75()) {
            Fixture f = fixture();
            AuditStoreException failure = assertThrows(AuditStoreException.class,
                    () -> new AuditAppendOnce(bare.dataSource()).appendOnce(f.source, f.draft));
            assertTrue(failure.getMessage().contains("schema is unavailable"), failure.getMessage());
            try (Connection c = bare.dataSource().getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("select count(*) from audit.event where chain_id = '" + f.chain + "'")) {
                rs.next();
                assertEquals(0, rs.getLong(1));
            }
        }
    }

    @Test
    void anUnreachableDatabaseIsAStoreFailure() {
        Fixture f = fixture();
        DataSource down = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    throw new SQLException("database unreachable");
                });
        assertThrows(AuditStoreException.class, () -> new AuditAppendOnce(down).appendOnce(f.source, f.draft));
    }

    // ------------------------------------------------------------------ retained references and least privilege

    @Test
    void retainedReferencesAndAppendOnlyGuardsRefuseChangeDeleteAndTruncate() throws SQLException {
        Fixture f = fixture();
        once.appendOnce(f.source, f.draft);
        assertEquals("42501", sqlState("update audit.source_binding set content_fingerprint = '" + "1".repeat(64)
                + "' where chain_id = '" + f.chain + "'"));
        assertEquals("42501", sqlState("delete from audit.source_binding where chain_id = '" + f.chain + "'"));
        // plain parent TRUNCATE now stops earlier at the retained child FK (0A000); the append-only trigger is
        // exercised by truncating the parents together with the child, in a transaction that always rolls back
        assertEquals("42501", sqlState("truncate audit.source_binding, audit.head_log, audit.event"));
        assertEquals("0A000", sqlState("truncate audit.event"));
        assertEquals("0A000", sqlState("truncate audit.head_log"));
        assertEquals(1, bindings(f.chain));
        assertEquals(1, events(f.chain));
    }

    @Test
    void theSelectInsertRoleCanAppendOnceButNotChangeAnythingAndWithoutBindingInsertNothingIsStored() throws SQLException {
        Fixture f = fixture();
        DataSource role = db.roleDataSource(ROLE, PASSWORD);
        AuditReceipt receipt = new AuditAppendOnce(role).appendOnce(f.source, f.draft);
        assertEquals(receipt, new AuditAppendOnce(role).appendOnce(f.source, f.draft));
        assertEquals(1, events(f.chain));

        for (String sql : List.of("update audit.source_binding set hash = hash", "delete from audit.source_binding",
                "update audit.event set resource_id = 'x'", "delete from audit.head_log")) {
            assertEquals("42501", sqlStateAs(role, sql), sql);
        }

        db.execute("revoke insert on audit.source_binding from " + ROLE);
        try {
            Fixture g = fixture();
            assertThrows(AuditStoreException.class, () -> new AuditAppendOnce(role).appendOnce(g.source, g.draft));
            assertNothingStored(g.chain);
        } finally {
            db.execute("grant insert on audit.source_binding to " + ROLE);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static <T> List<T> runTogether(List<? extends Callable<? extends T>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<? extends T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs statements as the owner with row triggers bypassed, as an attacker with owner rights would. */
    private static void tamper(String... statements) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("set session_replication_role = replica");
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static String sqlState(String sql) {
        return sqlStateAs(db.dataSource(), sql);
    }

    /** The SQLSTATE of {@code sql} run in a transaction that is always rolled back. */
    private static String sqlStateAs(DataSource source, String sql) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(sql);
                return "no error";
            } catch (SQLException e) {
                return e.getSQLState();
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    /**
     * A data source whose first connection fails its commit: after a real commit ({@code commitFirst}) or without one,
     * and whose later connections fail too when {@code failReadback}.
     */
    private static DataSource failingCommit(DataSource real, boolean commitFirst, boolean failReadback) {
        AtomicInteger connections = new AtomicInteger();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return invoke(method, real, args);
                    }
                    int n = connections.incrementAndGet();
                    if (n > 1 && failReadback) {
                        throw new SQLException("readback connection refused");
                    }
                    Connection connection = (Connection) invoke(method, real, args);
                    if (n > 1) {
                        return connection;
                    }
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if (m.getName().equals("commit")) {
                                    if (commitFirst) {
                                        connection.commit();
                                    }
                                    throw new SQLException("connection lost before the commit reply", "08006");
                                }
                                return invoke(m, connection, a);
                            });
                });
    }

    /** Refuses a second connection while one is still checked out, like a pool of size one. */
    private static DataSource oneConnectionAtATime(DataSource real) {
        return limitedTo(real, 1);
    }

    private static DataSource limitedTo(DataSource real, int size) {
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(size);
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return invoke(method, real, args);
                    }
                    if (!permits.tryAcquire(5, TimeUnit.SECONDS)) {
                        throw new SQLException("pool exhausted: a connection is still checked out");
                    }
                    Connection connection;
                    try {
                        connection = (Connection) invoke(method, real, args);
                    } catch (Throwable t) {
                        permits.release();
                        throw t;
                    }
                    AtomicInteger closed = new AtomicInteger();
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if (m.getName().equals("close") && closed.getAndIncrement() == 0) {
                                    try {
                                        return invoke(m, connection, a);
                                    } finally {
                                        permits.release();
                                    }
                                }
                                return invoke(m, connection, a);
                            });
                });
    }

    /** Every connection's first commit really commits and then reports a lost reply; later commits are normal. */
    private static DataSource failingEveryFirstCommit(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, real, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    AtomicInteger commits = new AtomicInteger();
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if (m.getName().equals("commit") && commits.getAndIncrement() == 0
                                        && !connection.isReadOnly()) {
                                    connection.commit();
                                    throw new SQLException("connection lost before the commit reply", "08006");
                                }
                                return invoke(m, connection, a);
                            });
                });
    }

    /** Waits at the barrier right after each connection's FIRST advisory lock (the source lock) is held, so both callers hold one first. */
    private static DataSource afterSourceLock(DataSource real, CyclicBarrier barrier) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, real, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean();
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                Object statement = invoke(m, connection, a);
                                if (!m.getName().equals("prepareStatement")
                                        || !String.valueOf(a[0]).contains("pg_advisory_xact_lock") || !first.compareAndSet(false, true)) {
                                    return statement;
                                }
                                PreparedStatement real2 = (PreparedStatement) statement;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                        new Class<?>[] {PreparedStatement.class}, (sp, sm, sa) -> {
                                            Object out = invoke(sm, real2, sa);
                                            if (sm.getName().equals("execute")) {
                                                barrier.await(30, TimeUnit.SECONDS);
                                            }
                                            return out;
                                        });
                            });
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
