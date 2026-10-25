package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Append-only below the application (M15): plain SQL cannot UPDATE, DELETE or TRUNCATE the audit tables, and the
 * rejection comes from the trigger, not from a missing privilege (the test runs as the table owner). Parent TRUNCATE
 * probes also name audit.source_binding when that retained child table exists (it holds foreign keys to the parents,
 * which would otherwise stop the statement earlier with 0A000), and always run in a transaction that rolls back.
 */
class AppendOnlyTest {

    private final AuditTrail trail = new AuditTrail(AuditTestDatabase.dataSource());

    @Test
    void plainUpdateOfAnEventFails() throws SQLException {
        String chain = seedChain();
        SQLException error = assertThrows(SQLException.class, () -> execute(
                "update audit.event set resource_id = 'forged' where chain_id = '" + chain + "'"));
        assertRejected(error);
        assertEquals("r-1", firstResourceId(chain));
    }

    @Test
    void plainDeleteOfAnEventFails() throws SQLException {
        String chain = seedChain();
        SQLException error = assertThrows(SQLException.class, () -> execute(
                "delete from audit.event where chain_id = '" + chain + "'"));
        assertRejected(error);
        assertEquals(1, count(chain));
    }

    @Test
    void truncateOfTheEventTableFails() throws SQLException {
        seedChain();
        SQLException error = assertThrows(SQLException.class, () -> executeRolledBack(truncate("audit.event")));
        assertRejected(error);
    }

    @Test
    void theHeadLogIsAppendOnlyToo() throws SQLException {
        String chain = seedChain();
        assertRejected(assertThrows(SQLException.class, () -> execute(
                "update audit.head_log set hash = repeat('a', 64) where chain_id = '" + chain + "'")));
        assertRejected(assertThrows(SQLException.class, () -> execute(
                "delete from audit.head_log where chain_id = '" + chain + "'")));
        assertRejected(assertThrows(SQLException.class, () -> executeRolledBack(truncate("audit.head_log"))));
    }

    @Test
    void eventsCanStillBeInserted() {
        assertEquals(1, trail.append(AuditTrailTest.draft(AuditTestDatabase.newChain(), "ok")).sequence());
    }

    private String seedChain() {
        String chain = AuditTestDatabase.newChain();
        trail.append(AuditTrailTest.draft(chain, "decision.allowed"));
        return chain;
    }

    private static void assertRejected(SQLException error) {
        assertEquals("42501", error.getSQLState(), error.getMessage());
        assertTrue(error.getMessage().contains("append-only"), error.getMessage());
    }

    /** TRUNCATE of the parent, together with audit.source_binding when the shared database has it. */
    private static String truncate(String parent) throws SQLException {
        return sourceBindingExists() ? "truncate audit.source_binding, " + parent : "truncate " + parent;
    }

    private static boolean sourceBindingExists() throws SQLException {
        try (Connection connection = AuditTestDatabase.connection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select to_regclass('audit.source_binding') is not null")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    /** Runs the statement in a transaction that is rolled back whether it fails or unexpectedly succeeds. */
    private static void executeRolledBack(String sql) throws SQLException {
        try (Connection connection = AuditTestDatabase.connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = AuditTestDatabase.connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(String chain) throws SQLException {
        try (Connection connection = AuditTestDatabase.connection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select count(*) from audit.event where chain_id = '" + chain + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String firstResourceId(String chain) throws SQLException {
        try (Connection connection = AuditTestDatabase.connection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "select resource_id from audit.event where chain_id = '" + chain + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
