package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Read-only check that the checkpoint tables the saver will use ARE the pinned {@code langgraph4j-postgres-saver-1.9.5}
 * public profile that migration V75.1 owns. It mirrors the catalog invariants of that migration (relations, columns,
 * keys, indexes, uniqueness; no triggers, rules, row security, inheritance or extra constraints) against the live
 * catalog, never against Flyway history, so a store that drifted after migration is refused.
 *
 * <p>What it does not do: it executes no DDL, repairs nothing, falls back to nothing, and reads no checkpoint row or
 * state. It resolves the saver's unqualified table names the way the saver does, so a differing search path or a
 * shadowing relation is a refusal. Only {@code public} is supported.
 *
 * <p>The first statement is {@code LOCK ... IN ACCESS SHARE MODE NOWAIT} on both tables. That conflicts only with
 * {@code ACCESS EXCLUSIVE} DDL (refused at once, never waited for); an ordinary {@code ROW EXCLUSIVE} writer is
 * unaffected, so a running graph keeps working. The lock lives only as long as the caller's transaction, which is the
 * whole guarantee: the result is a statement about that transaction on that connection, not about other pooled
 * connections' search paths and not about later administrator DDL. Profile compatibility is neither caller authority
 * nor permission to run or retry anything.
 */
public final class CheckpointProfileVerifier {

    /** Fixed refusal codes. */
    public enum Reason {
        /** The store could not be read or locked for the check. */
        UNAVAILABLE("unavailable"),
        /** {@code public} is not the connection's current schema. */
        NAMESPACE("namespace"),
        /** A table is absent (this includes a partial store). */
        MISSING("missing"),
        /** The saver's unqualified names resolve to something other than the public tables. */
        SHADOWED("shadowed"),
        /** Conflicting DDL holds or awaits a lock on the tables. */
        LOCKED("locked"),
        /** A relation is not a plain permanent table, or has row security or rules. */
        RELATION("relation"),
        /** A trigger, inheritance or a constraint kind outside the profile. */
        UNSUPPORTED_OBJECT("unsupported-object"),
        COLUMNS("columns"),
        KEYS("keys"),
        INDEXES("indexes"),
        UNIQUENESS("uniqueness");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private record Col(String type, boolean notNull, String defaultValue) { }

    private static final Map<String, Col> COLUMNS = Map.ofEntries(
            Map.entry("lg4jthread.thread_id", new Col("uuid", true, null)),
            Map.entry("lg4jthread.thread_name", new Col("character varying(255)", false, null)),
            Map.entry("lg4jthread.is_released", new Col("boolean", true, "false")),
            Map.entry("lg4jcheckpoint.checkpoint_id", new Col("uuid", true, null)),
            Map.entry("lg4jcheckpoint.parent_checkpoint_id", new Col("uuid", false, null)),
            Map.entry("lg4jcheckpoint.thread_id", new Col("uuid", true, null)),
            Map.entry("lg4jcheckpoint.node_id", new Col("character varying(255)", false, null)),
            Map.entry("lg4jcheckpoint.next_node_id", new Col("character varying(255)", false, null)),
            Map.entry("lg4jcheckpoint.state_data", new Col("text", true, null)),
            Map.entry("lg4jcheckpoint.state_content_type", new Col("character varying(100)", true, null)),
            Map.entry("lg4jcheckpoint.saved_at", new Col("timestamp with time zone", false, "CURRENT_TIMESTAMP")));

    private record Index(long relation, boolean unique, String key, int keyCount, String options, String predicate,
                         String first, String second) { }

    private CheckpointProfileVerifier() {
    }

    /**
     * Checks the profile in its own short read-only transaction on a connection from the data source, and gives the
     * connection back as found.
     *
     * @throws CheckpointProfileException the store is not the pinned profile or could not be checked
     */
    public static void verify(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            int isolation = connection.getTransactionIsolation();
            try {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                verify(connection);
                connection.rollback();
            } finally {
                try {
                    if (!connection.isClosed()) {
                        connection.rollback();
                        connection.setReadOnly(readOnly);
                        connection.setTransactionIsolation(isolation);
                        connection.setAutoCommit(autoCommit);
                    }
                } catch (SQLException ignored) {
                    // the pool discards a connection that is closed in this state; the check's own result stands
                }
            }
        } catch (SQLException unreadable) {
            throw new CheckpointProfileException(Reason.UNAVAILABLE, unreadable);
        }
    }

    /**
     * Checks the profile inside the caller's transaction, which must be open, read-only and not yet have run a query
     * (so that the lock precedes any snapshot). Nothing is committed or rolled back here: the lock stays until the
     * caller ends the transaction, and after a failure the caller rolls back.
     *
     * @throws CheckpointProfileException the store is not the pinned profile or could not be checked
     */
    public static void verify(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        try {
            if (connection.getAutoCommit() || !connection.isReadOnly()) {
                throw new IllegalArgumentException("the profile check needs an open read-only transaction");
            }
            check(connection);
        } catch (SQLException unreadable) {
            throw new CheckpointProfileException(Reason.UNAVAILABLE, unreadable);
        }
    }

    private static void check(Connection c) throws SQLException {
        lock(c);
        long thread;
        long checkpoint;
        long namespace;
        try (PreparedStatement s = statement(c, "select to_regclass('public.lg4jthread')::oid,"
                + " to_regclass('public.lg4jcheckpoint')::oid, to_regnamespace('public')::oid,"
                + " current_schema() is not distinct from 'public',"
                + " to_regclass('lg4jthread') is not distinct from to_regclass('public.lg4jthread'),"
                + " to_regclass('lg4jcheckpoint') is not distinct from to_regclass('public.lg4jcheckpoint')");
                ResultSet rs = s.executeQuery()) {
            rs.next();
            thread = rs.getLong(1);
            boolean threadMissing = rs.wasNull();
            checkpoint = rs.getLong(2);
            boolean checkpointMissing = rs.wasNull();
            namespace = rs.getLong(3);
            boolean namespaceMissing = rs.wasNull();
            if (namespaceMissing || !rs.getBoolean(4)) {
                throw refuse(Reason.NAMESPACE);
            }
            if (threadMissing || checkpointMissing) {
                throw refuse(Reason.MISSING);
            }
            if (!rs.getBoolean(5) || !rs.getBoolean(6)) {
                throw refuse(Reason.SHADOWED);
            }
        }
        relations(c, thread, checkpoint, namespace);
        unsupported(c, thread, checkpoint);
        Map<String, Short> attnums = columns(c, thread, checkpoint);
        keys(c, thread, checkpoint, attnums);
        indexes(c, thread, checkpoint, namespace, attnums);
    }

    /** First statement of the transaction; a missing table or schema is reported as such, never waited for. */
    private static void lock(Connection c) throws SQLException {
        try (PreparedStatement s = statement(c,
                "lock table public.lg4jthread, public.lg4jcheckpoint in access share mode nowait")) {
            s.execute();
        } catch (SQLException failed) {
            String state = failed.getSQLState();
            if ("55P03".equals(state)) {
                throw new CheckpointProfileException(Reason.LOCKED, failed);
            }
            if ("42P01".equals(state)) {
                throw new CheckpointProfileException(Reason.MISSING, failed);
            }
            if ("3F000".equals(state)) {
                throw new CheckpointProfileException(Reason.NAMESPACE, failed);
            }
            throw failed;
        }
    }

    private static void relations(Connection c, long thread, long checkpoint, long namespace) throws SQLException {
        try (PreparedStatement s = statement(c, "select count(*) from pg_catalog.pg_class where oid in (?,?)"
                + " and relkind = 'r' and relpersistence = 'p' and relnamespace = ? and not relrowsecurity"
                + " and not relforcerowsecurity and not relhasrules")) {
            s.setLong(1, thread);
            s.setLong(2, checkpoint);
            s.setLong(3, namespace);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                if (rs.getInt(1) != 2) {
                    throw refuse(Reason.RELATION);
                }
            }
        }
    }

    private static void unsupported(Connection c, long thread, long checkpoint) throws SQLException {
        try (PreparedStatement s = statement(c, "select"
                + " exists(select 1 from pg_catalog.pg_trigger where tgrelid in (?,?) and not tgisinternal)"
                + " or exists(select 1 from pg_catalog.pg_inherits where inhrelid in (?,?) or inhparent in (?,?))"
                + " or exists(select 1 from pg_catalog.pg_constraint where conrelid in (?,?)"
                + " and contype not in ('p','f'))")) {
            for (int i = 1; i <= 7; i += 2) {
                s.setLong(i, thread);
                s.setLong(i + 1, checkpoint);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                if (rs.getBoolean(1)) {
                    throw refuse(Reason.UNSUPPORTED_OBJECT);
                }
            }
        }
    }

    /** Every live column of both tables must be exactly one profile column; returns each column's attribute number. */
    private static Map<String, Short> columns(Connection c, long thread, long checkpoint) throws SQLException {
        Map<String, Short> attnums = new HashMap<>();
        try (PreparedStatement s = statement(c, "select r.relname || '.' || a.attname, a.attnum,"
                + " pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull, a.attgenerated::text,"
                + " a.attidentity::text, a.attcollation = t.typcollation,"
                + " pg_catalog.pg_get_expr(d.adbin, d.adrelid)"
                + " from pg_catalog.pg_attribute a join pg_catalog.pg_class r on r.oid = a.attrelid"
                + " join pg_catalog.pg_type t on t.oid = a.atttypid"
                + " left join pg_catalog.pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum"
                + " where a.attrelid in (?,?) and a.attnum > 0 and not a.attisdropped")) {
            s.setLong(1, thread);
            s.setLong(2, checkpoint);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    Col wanted = COLUMNS.get(rs.getString(1));
                    if (wanted == null || attnums.put(rs.getString(1), rs.getShort(2)) != null
                            || !wanted.type().equals(rs.getString(3)) || wanted.notNull() != rs.getBoolean(4)
                            || !rs.getString(5).isEmpty() || !rs.getString(6).isEmpty() || !rs.getBoolean(7)
                            || !Objects.equals(wanted.defaultValue(), rs.getString(8))) {
                        throw refuse(Reason.COLUMNS);
                    }
                }
            }
        }
        if (attnums.size() != COLUMNS.size()) {
            throw refuse(Reason.COLUMNS);
        }
        return attnums;
    }

    private static void keys(Connection c, long thread, long checkpoint, Map<String, Short> attnums)
            throws SQLException {
        String threadKey = "{" + attnums.get("lg4jthread.thread_id") + "}";
        String checkpointKey = "{" + attnums.get("lg4jcheckpoint.checkpoint_id") + "}";
        String checkpointThread = "{" + attnums.get("lg4jcheckpoint.thread_id") + "}";
        boolean primaryThread = false;
        boolean primaryCheckpoint = false;
        int foreign = 0;
        boolean foreignOk = false;
        try (PreparedStatement s = statement(c, "select conrelid, contype::text, conkey::text, confrelid,"
                + " confkey::text, confdeltype::text, confupdtype::text, confmatchtype::text,"
                + " not condeferrable and not condeferred and convalidated"
                + " from pg_catalog.pg_constraint where conrelid in (?,?)")) {
            s.setLong(1, thread);
            s.setLong(2, checkpoint);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long relation = rs.getLong(1);
                    String type = rs.getString(2);
                    boolean immediate = rs.getBoolean(9);
                    if ("p".equals(type) && immediate) {
                        if (relation == thread && threadKey.equals(rs.getString(3))) {
                            primaryThread = true;
                        } else if (relation == checkpoint && checkpointKey.equals(rs.getString(3))) {
                            primaryCheckpoint = true;
                        }
                    } else if ("f".equals(type)) {
                        foreign++;
                        foreignOk = immediate && relation == checkpoint && rs.getLong(4) == thread
                                && checkpointThread.equals(rs.getString(3)) && threadKey.equals(rs.getString(5))
                                && "c".equals(rs.getString(6)) && "a".equals(rs.getString(7))
                                && "s".equals(rs.getString(8));
                    }
                }
            }
        }
        if (!primaryThread || !primaryCheckpoint || foreign != 1 || !foreignOk) {
            throw refuse(Reason.KEYS);
        }
    }

    private static void indexes(Connection c, long thread, long checkpoint, long namespace,
                                Map<String, Short> attnums) throws SQLException {
        short checkpointThread = attnums.get("lg4jcheckpoint.thread_id");
        short savedAt = attnums.get("lg4jcheckpoint.saved_at");
        short threadName = attnums.get("lg4jthread.thread_name");
        Map<String, Index> wanted = Map.of(
                "idx_lg4jcheckpoint_thread_id", new Index(checkpoint, false, Short.toString(checkpointThread),
                        1, "0", null, "uuid_ops", null),
                "idx_lg4jcheckpoint_thread_id_saved_at_desc", new Index(checkpoint, false,
                        checkpointThread + " " + savedAt, 2, "0 3", null, "uuid_ops", "timestamptz_ops"),
                "idx_unique_lg4jthread_thread_name_unreleased", new Index(thread, true, Short.toString(threadName),
                        1, "0", "(is_released = false)", "text_ops", null));
        Set<String> seen = new HashSet<>();
        int threadUnique = 0;
        int checkpointUnique = 0;
        try (PreparedStatement s = statement(c, "select x.relname, i.indrelid, x.relnamespace, am.amname,"
                + " i.indisvalid and i.indisready and i.indimmediate and not i.indisprimary,"
                + " i.indisunique, i.indnullsnotdistinct, i.indnkeyatts, i.indnatts, i.indkey::text,"
                + " i.indoption::text, i.indexprs is not null, pg_catalog.pg_get_expr(i.indpred, i.indrelid),"
                + " (select o.opcname from pg_catalog.pg_opclass o where o.oid = i.indclass[0]"
                + "   and o.opcnamespace = 'pg_catalog'::regnamespace and o.opcmethod = am.oid),"
                + " (select o.opcname from pg_catalog.pg_opclass o where o.oid = i.indclass[1]"
                + "   and o.opcnamespace = 'pg_catalog'::regnamespace and o.opcmethod = am.oid),"
                + " i.indcollation[0] is not distinct from (select a.attcollation from pg_catalog.pg_attribute a"
                + "   where a.attrelid = i.indrelid and a.attnum = i.indkey[0]),"
                + " i.indnkeyatts < 2 or i.indcollation[1] = 0"
                + " from pg_catalog.pg_index i join pg_catalog.pg_class x on x.oid = i.indexrelid"
                + " join pg_catalog.pg_am am on am.oid = x.relam where i.indrelid in (?,?)")) {
            s.setLong(1, thread);
            s.setLong(2, checkpoint);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long relation = rs.getLong(2);
                    boolean unique = rs.getBoolean(6);
                    if (unique) {
                        if (relation == thread) {
                            threadUnique++;
                        } else {
                            checkpointUnique++;
                        }
                    }
                    Index expected = wanted.get(rs.getString(1));
                    if (expected == null) {
                        continue; // other non-unique indexes are outside the profile contract, as in V75.1
                    }
                    boolean ok = seen.add(rs.getString(1)) && relation == expected.relation()
                            && rs.getLong(3) == namespace && "btree".equals(rs.getString(4)) && rs.getBoolean(5)
                            && unique == expected.unique() && !rs.getBoolean(7)
                            && rs.getInt(8) == expected.keyCount() && rs.getInt(9) == expected.keyCount()
                            && expected.key().equals(rs.getString(10)) && expected.options().equals(rs.getString(11))
                            && !rs.getBoolean(12) && Objects.equals(expected.predicate(), rs.getString(13))
                            && expected.first().equals(rs.getString(14))
                            && Objects.equals(expected.second(), rs.getString(15))
                            && rs.getBoolean(16) && rs.getBoolean(17);
                    if (!ok) {
                        throw refuse(Reason.INDEXES);
                    }
                }
            }
        }
        if (seen.size() != wanted.size()) {
            throw refuse(Reason.INDEXES);
        }
        if (threadUnique != 2 || checkpointUnique != 1) {
            throw refuse(Reason.UNIQUENESS);
        }
    }

    private static CheckpointProfileException refuse(Reason reason) {
        return new CheckpointProfileException(reason, null);
    }

    private static PreparedStatement statement(Connection c, String sql) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }
}
