package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Read-only view of what the research graph has left in its checkpoint store for one thread name (WP-103, proposal in
 * docs/handoffs/claude/WP-103-execution-adapter.md). It never invokes the graph and never calls a model, so a recovery
 * read can say what happened without causing new work.
 *
 * <p>It reads {@code lg4jthread} and {@code lg4jcheckpoint} directly, because the saver's own lookup only sees an
 * unreleased thread and a completed run is released. Every inspection runs in ONE read-only REPEATABLE READ snapshot, so
 * the run count, the checkpoint rows and the final state always describe the same moment. Several thread rows for one
 * name mean the graph was entered more than once, which an invocation must never do; that is reported as
 * {@link Kind#REPEATED} and never resolved by choosing a row.
 *
 * <p>The saver (pinned 1.9.5) leaves {@code parent_checkpoint_id} empty, so the store carries no causal order and
 * {@code saved_at} is not one. Order is therefore taken from the pinned linear graph itself: a run is only understood
 * when its checkpoints are exactly the first <i>k</i> transitions of {@link #CHAIN}, each once. Anything else is
 * {@link Kind#INCONSISTENT} and no checkpoint is chosen. The stored state is Java-serialized bytes in Base64 text: it is
 * validated (content type, bound, canonical Base64, stream header) and returned exactly as stored, never
 * deserialized and never re-encoded. Fingerprinting that text is deliberately NOT done here; the one shared
 * stored-text helper owned by the contracts area must be used once it is merged.
 *
 * <p>An unavailable, missing or partial checkpoint schema is a dependency failure and is refused with
 * {@link IllegalStateException}; it is never reported as "no checkpoints".
 *
 * <p>This is data, not authority: the caller must still hold a current execution permission, and the result says
 * nothing about who may see the output.
 */
@ApplicationScoped
public class ResearchRunInspector {

    /** What the checkpoint store shows for one thread name. */
    public enum Kind {
        /** No thread row exists: the graph never produced a checkpoint here. */
        NONE,
        /** One thread whose checkpoints are a proper prefix of the graph (or empty): in progress, failed or killed. */
        PARTIAL,
        /** One thread holding every transition of the graph, the last one leading to the end. See {@link Inspection#released()}. */
        COMPLETE,
        /** More than one thread row for the name: a repeated entry. Never chosen between. */
        REPEATED,
        /** One thread whose rows do not match the pinned graph or the pinned storage profile. Never interpreted. */
        INCONSISTENT
    }

    /**
     * Exact stored checkpoint state, private to the run. The text is the stored Base64 exactly as read.
     * {@link #toString()} does not show it.
     */
    public record StoredState(String contentType, String base64) {
        public StoredState {
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(base64, "base64");
        }

        @Override
        public String toString() {
            return "StoredState[contentType=" + contentType + ", length=" + base64.length() + "]";
        }
    }

    /**
     * The result. {@code runs} is the number of thread rows in the snapshot. For PARTIAL and COMPLETE, {@code runId}
     * is the physical thread UUID and, when a checkpoint exists, {@code checkpointId} the physical UUID of the last
     * transition in graph order, with {@code lastNode}, {@code nextNode} and {@code checkpointCount}. {@code released}
     * is the saver's own flag, reported as data: the graph releases a thread only when it ends normally, so a COMPLETE
     * run that is not released is a finished graph whose release never committed ("terminal-unreleased"), and a
     * released run that is not complete is INCONSISTENT. {@code finalState} is set only for COMPLETE. {@code reason} is
     * set only for INCONSISTENT and is a fixed code, never stored data.
     */
    public record Inspection(Kind kind, int runs, String runId, String checkpointId, String lastNode, String nextNode,
                             int checkpointCount, boolean released, StoredState finalState, String reason) {
    }

    /** The graph's start and end markers. */
    static final String START = "__START__";
    static final String END = "__END__";
    /** The research graph in order. A checkpoint is the pair (node that just ran, node that runs next). */
    static final List<String> CHAIN = List.of(START, "plan", "draft", "review", END);
    /** The only stored content type of the pinned saver profile. */
    static final String CONTENT_TYPE = "application/octet-stream";
    /** Largest stored state text read, in characters. Deliberately tighter than the shared contract's bound. */
    static final int MAX_STATE_CHARS = 1_048_576;
    /** A valid run has at most one checkpoint per transition; more rows than this are refused unread. */
    static final int MAX_CHECKPOINT_ROWS = CHAIN.size() + 4;
    static final int MAX_THREAD_NAME = 255;
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    /** Columns the inspector reads, with the exact types the pinned saver creates. */
    private static final Map<String, String> THREAD_COLUMNS = Map.of(
            "thread_id", "uuid", "thread_name", "character varying(255)", "is_released", "boolean");
    private static final Map<String, String> CHECKPOINT_COLUMNS = Map.of(
            "checkpoint_id", "uuid", "thread_id", "uuid", "node_id", "character varying(255)",
            "next_node_id", "character varying(255)", "state_data", "text",
            "state_content_type", "character varying(100)");

    private final DataSource dataSource;

    @Inject
    public ResearchRunInspector(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Inspects the thread by its exact name.
     *
     * @throws IllegalStateException the store or its schema could not be read; that is never "nothing happened"
     */
    public Inspection inspect(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        if (threadName.isEmpty() || threadName.length() > MAX_THREAD_NAME) {
            throw new IllegalArgumentException("thread name must be 1 to " + MAX_THREAD_NAME + " characters");
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            int isolation = connection.getTransactionIsolation();
            try {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                requireSchema(connection);
                Inspection result = read(connection, threadName);
                connection.rollback();
                return result;
            } finally {
                restore(connection, autoCommit, readOnly, isolation);
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("the checkpoint store could not be read", unreadable);
        }
    }

    private static Inspection read(Connection connection, String threadName) throws SQLException {
        int runs;
        try (PreparedStatement count = statement(connection,
                "select count(*) from lg4jthread where thread_name = ?")) {
            count.setString(1, threadName);
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                runs = rs.getInt(1);
            }
        }
        if (runs == 0) {
            return new Inspection(Kind.NONE, 0, null, null, null, null, 0, false, null, null);
        }
        if (runs > 1) {
            return new Inspection(Kind.REPEATED, runs, null, null, null, null, 0, false, null, null);
        }
        String runId = null;
        boolean released = false;
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement select = statement(connection,
                "select t.thread_id, t.is_released, c.checkpoint_id, c.node_id, c.next_node_id,"
                        + " c.state_content_type, length(c.state_data)"
                        + " from lg4jthread t left join lg4jcheckpoint c on c.thread_id = t.thread_id"
                        + " where t.thread_name = ? order by c.checkpoint_id limit ?")) {
            select.setString(1, threadName);
            select.setInt(2, MAX_CHECKPOINT_ROWS + 1);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    runId = rs.getString(1);
                    released = rs.getBoolean(2);
                    String checkpoint = rs.getString(3);
                    if (checkpoint != null) {
                        rows.add(new Row(checkpoint, rs.getString(4), rs.getString(5), rs.getString(6),
                                rs.getObject(7) == null ? -1 : rs.getInt(7)));
                    }
                }
            }
        }
        if (runId == null) {
            // the count saw one thread and this read saw none: one snapshot cannot disagree with itself
            throw new IllegalStateException("the checkpoint snapshot was not coherent");
        }
        if (rows.size() > MAX_CHECKPOINT_ROWS) {
            return inconsistent(runId, released, rows.size(), "too-many-checkpoints");
        }
        // transitions seen, by position in CHAIN; a transition seen twice is a repeated node
        Map<Integer, Row> byPosition = new HashMap<>();
        for (Row row : rows) {
            int position = position(row.node, row.next);
            if (position < 0) {
                return inconsistent(runId, released, rows.size(), "checkpoint-outside-graph");
            }
            if (byPosition.put(position, row) != null) {
                return inconsistent(runId, released, rows.size(), "repeated-transition");
            }
            if (!CONTENT_TYPE.equals(row.contentType)) {
                return inconsistent(runId, released, rows.size(), "unsupported-content-type");
            }
            if (row.length < 1 || row.length > MAX_STATE_CHARS) {
                return inconsistent(runId, released, rows.size(), "state-size-out-of-bounds");
            }
        }
        int count = rows.size();
        for (int p = 0; p < count; p++) {
            if (!byPosition.containsKey(p)) {
                return inconsistent(runId, released, count, "gap-in-graph-order");
            }
        }
        if (count == 0) {
            if (released) {
                return inconsistent(runId, released, 0, "released-before-end");
            }
            return new Inspection(Kind.PARTIAL, 1, runId, null, null, null, 0, false, null, null);
        }
        Row last = byPosition.get(count - 1);
        boolean complete = count == CHAIN.size() - 1;
        if (!complete) {
            if (released) {
                return inconsistent(runId, released, count, "released-before-end");
            }
            return new Inspection(Kind.PARTIAL, 1, runId, last.checkpointId, last.node, last.next, count, false,
                    null, null);
        }
        String text = state(connection, runId, last);
        String invalid = invalidState(text);
        if (invalid != null) {
            return inconsistent(runId, released, count, invalid);
        }
        return new Inspection(Kind.COMPLETE, 1, runId, last.checkpointId, last.node, last.next, count, released,
                new StoredState(CONTENT_TYPE, text), null);
    }

    /** The state text of one checkpoint of this run, read only when its stored length is within bounds. */
    private static String state(Connection connection, String runId, Row row) throws SQLException {
        try (PreparedStatement select = statement(connection,
                "select state_data from lg4jcheckpoint where checkpoint_id = ?::uuid and thread_id = ?::uuid"
                        + " and length(state_data) between 1 and ?")) {
            select.setString(1, row.checkpointId);
            select.setString(2, runId);
            select.setInt(3, MAX_STATE_CHARS);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("the checkpoint snapshot was not coherent");
                }
                return rs.getString(1);
            }
        }
    }

    /** Null when the text is canonical standard padded Base64 of a Java serialization stream; no object is created. */
    private static String invalidState(String text) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(text);
            if (!Base64.getEncoder().encodeToString(decoded).equals(text)) {
                return "state-not-canonical-base64";
            }
        } catch (IllegalArgumentException malformed) {
            return "state-not-canonical-base64";
        }
        boolean stream = decoded.length >= 4 && (decoded[0] & 0xFF) == 0xAC && (decoded[1] & 0xFF) == 0xED
                && decoded[2] == 0 && decoded[3] == 5;
        return stream ? null : "state-not-java-stream";
    }

    private static int position(String node, String next) {
        if (node == null || next == null) {
            return -1;
        }
        int from = CHAIN.indexOf(node);
        return from >= 0 && from + 1 < CHAIN.size() && CHAIN.get(from + 1).equals(next) ? from : -1;
    }

    private static Inspection inconsistent(String runId, boolean released, int count, String reason) {
        return new Inspection(Kind.INCONSISTENT, 1, runId, null, null, null, count, released, null, reason);
    }

    /** Both tables must resolve like the saver's own unqualified names, with every column the inspector reads. */
    private static void requireSchema(Connection connection) throws SQLException {
        Map<String, String> thread = columns(connection, "lg4jthread");
        Map<String, String> checkpoint = columns(connection, "lg4jcheckpoint");
        if (!thread.entrySet().containsAll(THREAD_COLUMNS.entrySet())
                || !checkpoint.entrySet().containsAll(CHECKPOINT_COLUMNS.entrySet())) {
            throw new IllegalStateException("the checkpoint schema is missing or does not match the saver profile");
        }
    }

    private static Map<String, String> columns(Connection connection, String table) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        try (PreparedStatement select = statement(connection,
                "select a.attname, format_type(a.atttypid, a.atttypmod) from pg_attribute a"
                        + " where a.attrelid = to_regclass(?) and a.attnum > 0 and not a.attisdropped")) {
            select.setString(1, table);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    columns.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return columns;
    }

    private static PreparedStatement statement(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }

    /** Puts the pooled connection back as found; a failed restore must not hide the real result. */
    private static void restore(Connection connection, boolean autoCommit, boolean readOnly, int isolation) {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
                connection.setReadOnly(readOnly);
                connection.setTransactionIsolation(isolation);
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ignored) {
            // the connection is discarded by its pool when it is closed in this state
        }
    }

    private record Row(String checkpointId, String node, String next, String contentType, int length) {
    }
}
