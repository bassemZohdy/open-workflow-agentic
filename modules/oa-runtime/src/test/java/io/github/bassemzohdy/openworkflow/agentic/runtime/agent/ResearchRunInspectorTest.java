package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchOutputFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector.Inspection;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector.Kind;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The inspector against the real checkpoint tables. Runs are written by the real graph with a scripted model; the
 * structure, encoding and schema cases write the saver's own tables directly, because the real graph cannot produce
 * them. The database is migrated first (see {@link AgentTestDatabase}), whichever test of the build runs first.
 */
class ResearchRunInspectorTest {

    private static final String OK = "{\"text\":\"1. a 2. b 3. c\",\"actions\":[],\"escalation\":null}";
    private static final String CT = ResearchRunInspector.CONTENT_TYPE;
    /** A canonical Base64 text that starts like a Java serialization stream. */
    private static final String STREAM = Base64.getEncoder()
            .encodeToString(new byte[] {(byte) 0xAC, (byte) 0xED, 0, 5, 0x70});
    private static final String OTHER_STREAM = Base64.getEncoder()
            .encodeToString(new byte[] {(byte) 0xAC, (byte) 0xED, 0, 5, 0x71, 0x71});

    private static DataSource ds;

    @BeforeAll
    static void migratedDatabase() throws Exception {
        ds = AgentTestDatabase.dataSource();
    }

    private static ResearchAgent agent(DataSource dataSource, LlmClient llm) throws Exception {
        ResearchAgent agent = new ResearchAgent();
        agent.llm = GovernedFixtures.allowing(llm);
        agent.checkpointSchema = "public"; // manual fixture: what the configuration injects in production
        agent.dataSource = dataSource;
        agent.outputValidator = new AgentOutputValidator(new ObjectMapper(), target -> false);
        agent.build();
        return agent;
    }

    /** A model that answers the first {@code ok} calls and then throws. */
    private static LlmClient failingAfter(int ok, AtomicInteger calls) {
        return (system, user) -> {
            if (calls.getAndIncrement() >= ok) {
                throw new IllegalStateException("model down");
            }
            return OK;
        };
    }

    // ------------------------------------------------------------------ runs written by the real graph

    @Test
    void aThreadThatNeverRanIsNone() {
        Inspection result = new ResearchRunInspector(ds).inspect("never-" + UUID.randomUUID());
        assertEquals(Kind.NONE, result.kind());
        assertEquals(0, result.runs());
        assertNull(result.runId());
    }

    @Test
    void aCompletedRunIsCompleteWithItsPhysicalIdsAndTheExactStoredState() throws Exception {
        String thread = "done-" + UUID.randomUUID();
        agent(ds, (s, u) -> OK).run(thread, "topic");
        ResearchRunInspector inspector = new ResearchRunInspector(ds);
        Inspection first = inspector.inspect(thread);
        assertEquals(Kind.COMPLETE, first.kind());
        assertEquals(1, first.runs());
        assertEquals("review", first.lastNode());
        assertEquals("__END__", first.nextNode());
        assertEquals(4, first.checkpointCount());
        assertTrue(first.released(), "the graph releases a thread when it ends normally");
        // both physical ids are the real row keys, not names
        assertEquals(first.runId(), UUID.fromString(first.runId()).toString());
        assertEquals(first.checkpointId(), UUID.fromString(first.checkpointId()).toString());
        assertEquals(first.runId(), single("select thread_id::text from lg4jthread where thread_name = ?", thread));
        assertEquals(first.checkpointId(), single("select checkpoint_id::text from lg4jcheckpoint where thread_id = ?::uuid"
                + " and next_node_id = '__END__'", first.runId()));
        // the state is the stored text byte for byte, with its media type, and is not shown by toString
        assertEquals(CT, first.finalState().contentType());
        assertEquals(single("select state_data from lg4jcheckpoint where checkpoint_id = ?::uuid", first.checkpointId()),
                first.finalState().base64());
        assertFalse(first.toString().contains(first.finalState().base64()));
        assertEquals(first, inspector.inspect(thread));
    }

    @Test
    void theCompletedRunsFingerprintIsTheSharedHelperOverTheExactStoredTextAndOnlyThat() throws Exception {
        String thread = "fp-" + UUID.randomUUID();
        String other = "fp-" + UUID.randomUUID();
        agent(ds, (s, u) -> OK).run(thread, "topic");
        agent(ds, (s, u) -> "{\"text\":\"1. x 2. y 3. z\",\"actions\":[],\"escalation\":null}").run(other, "topic");
        ResearchRunInspector inspector = new ResearchRunInspector(ds);
        Inspection first = inspector.inspect(thread);
        String stored = single("select state_data from lg4jcheckpoint where checkpoint_id = ?::uuid", first.checkpointId());

        String expected = ResearchOutputFingerprint.of(CT, stored);
        assertEquals(expected, first.finalState().fingerprint());
        assertTrue(expected.matches("[0-9a-f]{64}"));
        assertEquals(expected, inspector.inspect(thread).finalState().fingerprint(), "stable for the same retained text");
        assertFalse(expected.equals(inspector.inspect(other).finalState().fingerprint()),
                "a different retained graph state has a different fingerprint");
        assertFalse(first.toString().contains(expected), "a digest is data and is not part of the diagnostic text");
    }

    @Test
    void aRunThatFailedPartwayIsPartialAndNotComplete() throws Exception {
        String thread = "failed-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> agent(ds, failingAfter(1, calls)).run(thread, "topic"));
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        // measured: the graph leaves the checkpoint before the failed node, and the thread stays open
        assertEquals(Kind.PARTIAL, result.kind());
        assertEquals("plan", result.lastNode());
        assertEquals("draft", result.nextNode());
        assertEquals(2, result.checkpointCount());
        assertFalse(result.released());
        assertNotNull(result.runId());
        assertNull(result.finalState());
    }

    @Test
    void aRunThatFailedInTheFirstNodeIsPartialAtStart() throws Exception {
        String thread = "failed-first-" + UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> agent(ds, failingAfter(0, new AtomicInteger())).run(thread, "topic"));
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.PARTIAL, result.kind());
        assertEquals("__START__", result.lastNode());
        assertEquals("plan", result.nextNode());
        assertEquals(1, result.checkpointCount());
    }

    @Test
    void anEntryRepeatedAfterCompletionIsRepeated() throws Exception {
        String thread = "twice-" + UUID.randomUUID();
        ResearchAgent agent = agent(ds, (s, u) -> OK);
        agent.run(thread, "topic");
        agent.run(thread, "topic");
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.REPEATED, result.kind());
        assertEquals(2, result.runs());
        assertNull(result.finalState());
        assertNull(result.checkpointId());
    }

    @Test
    void anUnreadableStoreIsAFailureNotNone() {
        org.postgresql.ds.PGSimpleDataSource broken = new org.postgresql.ds.PGSimpleDataSource();
        broken.setUrl("jdbc:postgresql://localhost:1/none");
        assertThrows(IllegalStateException.class, () -> new ResearchRunInspector(broken).inspect("x"));
    }

    @Test
    void aThreadNameOutsideTheColumnBoundIsRefused() {
        ResearchRunInspector inspector = new ResearchRunInspector(ds);
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect(""));
        assertThrows(IllegalArgumentException.class, () -> inspector.inspect("x".repeat(256)));
    }

    // ------------------------------------------------------------------ finding 2: one coherent snapshot

    @Test
    void aRunEnteredBetweenTheCountAndTheReadIsNotBlendedIntoTheAnswer() throws Exception {
        String thread = "race-new-run-" + UUID.randomUUID();
        agent(ds, (s, u) -> OK).run(thread, "topic");
        AtomicBoolean fired = new AtomicBoolean();
        // after the run count, another connection commits a second run for the same name, then the rows are read
        DataSource perturbed = perturbBefore(ds, "select t.thread_id", () -> {
            fired.set(true);
            UUID second = insertThread(thread, true);
            insertCheckpoint(second, "__START__", "plan", CT, STREAM, Instant.now());
        });
        Inspection inSnapshot = new ResearchRunInspector(perturbed).inspect(thread);
        assertTrue(fired.get());
        assertEquals(Kind.COMPLETE, inSnapshot.kind(), "the snapshot predates the second run");
        assertEquals(1, inSnapshot.runs());
        // control: the second run really committed, and a later inspection reports it
        Inspection later = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.REPEATED, later.kind());
        assertEquals(2, later.runs());
    }

    @Test
    void aStateChangedAfterTheMetadataReadDoesNotChangeTheAnswer() throws Exception {
        String thread = "race-state-" + UUID.randomUUID();
        agent(ds, (s, u) -> OK).run(thread, "topic");
        String original = new ResearchRunInspector(ds).inspect(thread).finalState().base64();
        AtomicBoolean fired = new AtomicBoolean();
        DataSource perturbed = perturbBefore(ds, "select state_data", () -> {
            fired.set(true);
            execute("update lg4jcheckpoint set state_data = ? where thread_id = (select thread_id from lg4jthread"
                    + " where thread_name = ?) and next_node_id = '__END__'", OTHER_STREAM, thread);
        });
        Inspection inSnapshot = new ResearchRunInspector(perturbed).inspect(thread);
        assertTrue(fired.get());
        assertEquals(original, inSnapshot.finalState().base64(), "the state belongs to the same snapshot as the rows");
        assertEquals(OTHER_STREAM, new ResearchRunInspector(ds).inspect(thread).finalState().base64());
    }

    @Test
    void theConnectionIsHandedBackAsFoundAndTheReadNeverWrites() throws Exception {
        String thread = "hygiene-" + UUID.randomUUID();
        agent(ds, (s, u) -> OK).run(thread, "topic");
        try (Connection held = ds.getConnection()) {
            DataSource single = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[] {DataSource.class}, (p, m, a) -> {
                        if (m.getName().equals("getConnection")) {
                            return Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class}, (cp, cm, ca) ->
                                            cm.getName().equals("close") ? null : call(cm, held, ca));
                        }
                        return call(m, ds, a);
                    });
            assertTrue(held.getAutoCommit());
            Inspection result = new ResearchRunInspector(single).inspect(thread);
            assertEquals(Kind.COMPLETE, result.kind());
            assertTrue(held.getAutoCommit());
            assertFalse(held.isReadOnly());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, held.getTransactionIsolation());
        }
    }

    // ------------------------------------------------------------------ finding 3: structure, encoding, bounds

    @Test
    void theGraphOrderNotTimestampsOrIdsPicksTheFinalCheckpoint() throws Exception {
        // all four transitions with the SAME saved_at and ids that sort against the graph order
        String thread = "equal-time-" + UUID.randomUUID();
        UUID run = insertThread(thread, true);
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        // ids that sort against the graph order; the low part is random so the test can run again on the same database
        long tail = UUID.randomUUID().getMostSignificantBits() & 0xFFFFFFFFFFFFL;
        List<UUID> ids = List.of(UUID.fromString(String.format("ffffffff-0000-4000-8000-%012x", tail)),
                UUID.fromString(String.format("eeeeeeee-0000-4000-8000-%012x", tail)),
                UUID.fromString(String.format("dddddddd-0000-4000-8000-%012x", tail)),
                UUID.fromString(String.format("00000000-0000-4000-8000-%012x", tail)));
        String[][] chain = {{"__START__", "plan"}, {"plan", "draft"}, {"draft", "review"}, {"review", "__END__"}};
        for (int i = 0; i < 4; i++) {
            insertCheckpoint(run, ids.get(i), chain[i][0], chain[i][1], CT, STREAM, same);
        }
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.COMPLETE, result.kind());
        assertEquals(ids.get(3).toString(), result.checkpointId(), "the review -> __END__ row, whatever its id or time");
        assertEquals(run.toString(), result.runId());
    }

    @Test
    void aFinishedGraphWhoseReleaseNeverCommittedIsCompleteAndUnreleased() throws Exception {
        String thread = "unreleased-end-" + UUID.randomUUID();
        agent(ds, (s, u) -> OK).run(thread, "topic");
        String stored = new ResearchRunInspector(ds).inspect(thread).finalState().base64();
        execute("update lg4jthread set is_released = false where thread_name = ?", thread);
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.COMPLETE, result.kind());
        assertFalse(result.released());
        assertEquals(stored, result.finalState().base64());
    }

    /**
     * Characterization of the pinned saver (1.9.5), measured, not a feature: entering a finished thread whose release
     * never committed does not return the stored result. It replays every node (three new model calls), appends four
     * more checkpoints to the same thread and only then releases it. Recovery of a terminal-unreleased run must
     * therefore READ the final checkpoint (this inspector) and never enter the graph again. If an upgrade changes this
     * behaviour, this test fails and the recovery design must be re-measured.
     */
    @Test
    void reEnteringAFinishedButUnreleasedThreadReplaysEveryNodeSoRecoveryMustReadAndNotRun() throws Exception {
        String thread = "reenter-unreleased-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        ResearchAgent agent = agent(ds, (s, u) -> {
            calls.incrementAndGet();
            return OK;
        });
        agent.run(thread, "topic");
        assertEquals(3, calls.get());
        execute("update lg4jthread set is_released = false where thread_name = ?", thread);
        calls.set(0);
        agent.run(thread, "topic");
        assertEquals(3, calls.get(), "every node ran again: this is duplicate model work, not a resume");
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.INCONSISTENT, result.kind(), "the inspector shows the replay instead of hiding it");
        assertEquals("repeated-transition", result.reason());
        assertEquals(8, result.checkpointCount());
    }

    @Test
    void aReleasedRunThatDidNotReachTheEndIsInconsistent() throws Exception {
        String thread = "released-partial-" + UUID.randomUUID();
        UUID run = insertThread(thread, true);
        insertCheckpoint(run, "__START__", "plan", CT, STREAM, Instant.now());
        assertInconsistent(thread, "released-before-end");
    }

    @Test
    void aTransitionRepeatedInOneRunIsInconsistent() throws Exception {
        String thread = "repeat-node-" + UUID.randomUUID();
        UUID run = insertThread(thread, false);
        insertCheckpoint(run, "__START__", "plan", CT, STREAM, Instant.now());
        insertCheckpoint(run, "plan", "draft", CT, STREAM, Instant.now());
        insertCheckpoint(run, "plan", "draft", CT, STREAM, Instant.now());
        assertInconsistent(thread, "repeated-transition");
    }

    @Test
    void aGapInTheGraphOrderIsInconsistent() throws Exception {
        String thread = "gap-" + UUID.randomUUID();
        UUID run = insertThread(thread, false);
        insertCheckpoint(run, "__START__", "plan", CT, STREAM, Instant.now());
        insertCheckpoint(run, "draft", "review", CT, STREAM, Instant.now());
        assertInconsistent(thread, "gap-in-graph-order");
    }

    @Test
    void anUnrelatedNodeLeadingToTheEndDoesNotMakeTheResearchGraphComplete() throws Exception {
        String thread = "unrelated-end-" + UUID.randomUUID();
        UUID run = insertThread(thread, true);
        insertCheckpoint(run, "other", "__END__", CT, STREAM, Instant.now());
        assertInconsistent(thread, "checkpoint-outside-graph");
    }

    @Test
    void anUnsupportedContentTypeIsInconsistent() throws Exception {
        String thread = "media-" + UUID.randomUUID();
        UUID run = insertThread(thread, false);
        insertCheckpoint(run, "__START__", "plan", "application/json", STREAM, Instant.now());
        assertInconsistent(thread, "unsupported-content-type");
    }

    @Test
    void aStateThatIsNotCanonicalBase64OfAJavaStreamIsInconsistentAndNeverReturned() throws Exception {
        for (String[] bad : new String[][] {{"not base64 !!", "state-not-canonical-base64"},
                {"rO0ABXA", "state-not-canonical-base64"}, // decodes, but re-encodes with padding: not the stored text
                {Base64.getEncoder().encodeToString("hello".getBytes()), "state-not-java-stream"}}) {
            String thread = "bad-state-" + UUID.randomUUID();
            UUID run = insertThread(thread, true);
            insertCheckpoint(run, "__START__", "plan", CT, STREAM, Instant.now());
            insertCheckpoint(run, "plan", "draft", CT, STREAM, Instant.now());
            insertCheckpoint(run, "draft", "review", CT, STREAM, Instant.now());
            insertCheckpoint(run, "review", "__END__", CT, bad[0], Instant.now());
            Inspection result = new ResearchRunInspector(ds).inspect(thread);
            assertEquals(Kind.INCONSISTENT, result.kind(), bad[0]);
            assertNull(result.finalState());
            assertNotNull(result.reason());
        }
    }

    @Test
    void anOversizedStateIsRefusedWithoutBeingLoaded() throws Exception {
        String thread = "huge-" + UUID.randomUUID();
        UUID run = insertThread(thread, true);
        insertCheckpoint(run, "__START__", "plan", CT, STREAM, Instant.now());
        insertCheckpoint(run, "plan", "draft", CT, STREAM, Instant.now());
        insertCheckpoint(run, "draft", "review", CT, STREAM, Instant.now());
        insertCheckpoint(run, "review", "__END__", CT, "A".repeat(ResearchRunInspector.MAX_STATE_CHARS + 4), Instant.now());
        AtomicBoolean loaded = new AtomicBoolean();
        DataSource watched = perturbBefore(ds, "select state_data", () -> loaded.set(true));
        Inspection result = new ResearchRunInspector(watched).inspect(thread);
        assertEquals(Kind.INCONSISTENT, result.kind());
        assertEquals("state-size-out-of-bounds", result.reason());
        assertFalse(loaded.get(), "the oversized text was never selected");
    }

    @Test
    void moreCheckpointRowsThanTheGraphCanProduceAreRefusedUnread() throws Exception {
        String thread = "flood-" + UUID.randomUUID();
        UUID run = insertThread(thread, false);
        for (int i = 0; i < ResearchRunInspector.MAX_CHECKPOINT_ROWS + 3; i++) {
            insertCheckpoint(run, "plan", "draft", CT, STREAM, Instant.now());
        }
        assertInconsistent(thread, "too-many-checkpoints");
    }

    // ------------------------------------------------------------------ finding 4: unavailable schema is refused

    @Test
    void aMissingCheckpointSchemaIsRefusedNotNone() throws Exception {
        String schema = "oa_none_" + UUID.randomUUID().toString().replace('-', '_');
        execute("create schema " + schema);
        try {
            ResearchRunInspector inspector = new ResearchRunInspector(AgentTestDatabase.inSchema(schema));
            assertThrows(IllegalStateException.class, () -> inspector.inspect("anything"));
        } finally {
            execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void aPartialCheckpointSchemaIsRefused() throws Exception {
        String schema = "oa_partial_" + UUID.randomUUID().toString().replace('-', '_');
        execute("create schema " + schema);
        execute("create table " + schema + ".lg4jthread (thread_id uuid primary key,"
                + " thread_name varchar(255), is_released boolean not null default false)");
        try {
            ResearchRunInspector inspector = new ResearchRunInspector(AgentTestDatabase.inSchema(schema));
            assertThrows(IllegalStateException.class, () -> inspector.inspect("anything"));
        } finally {
            execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void aCheckpointSchemaWithAnotherColumnTypeIsRefused() throws Exception {
        String schema = "oa_types_" + UUID.randomUUID().toString().replace('-', '_');
        execute("create schema " + schema);
        execute("create table " + schema + ".lg4jthread (thread_id uuid primary key,"
                + " thread_name text, is_released boolean not null default false)");
        execute("create table " + schema + ".lg4jcheckpoint (checkpoint_id uuid primary key, thread_id uuid not null,"
                + " node_id varchar(255), next_node_id varchar(255), state_data text not null,"
                + " state_content_type varchar(100) not null)");
        try {
            ResearchRunInspector inspector = new ResearchRunInspector(AgentTestDatabase.inSchema(schema));
            assertThrows(IllegalStateException.class, () -> inspector.inspect("anything"));
        } finally {
            execute("drop schema " + schema + " cascade");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assertInconsistent(String thread, String reason) {
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.INCONSISTENT, result.kind(), reason);
        assertEquals(reason, result.reason());
        assertNull(result.finalState());
        assertNull(result.checkpointId());
    }

    private static UUID insertThread(String name, boolean released) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("insert into lg4jthread (thread_id, thread_name, is_released) values (?::uuid, ?, ?)", id.toString(),
                name, released);
        return id;
    }

    private static void insertCheckpoint(UUID run, String node, String next, String contentType, String state,
                                         Instant at) throws SQLException {
        insertCheckpoint(run, UUID.randomUUID(), node, next, contentType, state, at);
    }

    private static void insertCheckpoint(UUID run, UUID id, String node, String next, String contentType, String state,
                                         Instant at) throws SQLException {
        execute("insert into lg4jcheckpoint (checkpoint_id, thread_id, node_id, next_node_id, state_data,"
                + " state_content_type, saved_at) values (?::uuid, ?::uuid, ?, ?, ?, ?, ?)", id.toString(),
                run.toString(), node, next, state, contentType, Timestamp.from(at));
    }

    private static String single(String sql, String arg) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, arg);
            try (var rs = s.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            s.execute();
        }
    }

    /** Runs {@code action} (committed, on its own connection) just before the first statement starting with {@code prefix}. */
    private static DataSource perturbBefore(DataSource target, String prefix, ThrowingRunnable action) {
        AtomicBoolean done = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (p, m, a) -> {
                    Object result = call(m, target, a);
                    if (!m.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection actual = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("prepareStatement") && ca[0] instanceof String sql
                                        && sql.startsWith(prefix) && done.compareAndSet(false, true)) {
                                    action.run();
                                }
                                return call(cm, actual, ca);
                            });
                });
    }

    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
