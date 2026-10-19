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
 * rejection comes from the trigger, not from a missing privilege (the test runs as the table owner).
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
        SQLException error = assertThrows(SQLException.class, () -> execute("truncate audit.event"));
        assertRejected(error);
    }

    @Test
    void theHeadLogIsAppendOnlyToo() throws SQLException {
        String chain = seedChain();
        assertRejected(assertThrows(SQLException.class, () -> execute(
                "update audit.head_log set hash = repeat('a', 64) where chain_id = '" + chain + "'")));
        assertRejected(assertThrows(SQLException.class, () -> execute(
                "delete from audit.head_log where chain_id = '" + chain + "'")));
        assertRejected(assertThrows(SQLException.class, () -> execute("truncate audit.head_log")));
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
