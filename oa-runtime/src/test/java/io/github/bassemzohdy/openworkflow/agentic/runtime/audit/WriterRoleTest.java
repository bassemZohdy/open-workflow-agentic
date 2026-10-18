package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The deployment hardening of docs/audit.md, proven on a real role: a writer that has only SELECT and INSERT on the
 * audit tables can append events, and the database refuses every change by privilege (not only by trigger).
 */
class WriterRoleTest {

    static final String ROLE = "oa_audit_writer_test";
    static final String PASSWORD = "writer-test-only";

    @BeforeAll
    static void createRole() throws SQLException {
        execute("do $$ begin if not exists (select 1 from pg_roles where rolname = '" + ROLE + "') then "
                + "create role " + ROLE + " login password '" + PASSWORD + "'; end if; end $$");
        execute("grant usage on schema audit to " + ROLE);
        execute("grant select, insert on audit.event, audit.head_log to " + ROLE);
    }

    @AfterAll
    static void dropRole() throws SQLException {
        execute("revoke all on all tables in schema audit from " + ROLE);
        execute("revoke usage on schema audit from " + ROLE);
        execute("drop role if exists " + ROLE);
    }

    @Test
    void theWriterRoleCanAppendThroughTheTrail() {
        AuditTrail writer = new AuditTrail(writerDataSource());
        AuditEvent stored = writer.append(AuditTrailTest.draft(AuditTestDatabase.newChain(), "decision.allowed"));
        assertEquals(1, stored.sequence());
    }

    @Test
    void theWriterRoleCannotUpdateAnEvent() throws SQLException {
        String chain = AuditTestDatabase.newChain();
        new AuditTrail(AuditTestDatabase.dataSource()).append(AuditTrailTest.draft(chain, "decision.allowed"));

        SQLException error = assertThrows(SQLException.class, () -> executeAsWriter(
                "update audit.event set resource_id = 'forged' where chain_id = '" + chain + "'"));
        assertEquals("42501", error.getSQLState());
        assertTrue(error.getMessage().contains("permission denied"), error.getMessage());
    }

    @Test
    void theWriterRoleCannotDeleteOrTruncate() throws SQLException {
        String chain = AuditTestDatabase.newChain();
        new AuditTrail(AuditTestDatabase.dataSource()).append(AuditTrailTest.draft(chain, "decision.allowed"));

        assertTrue(assertThrows(SQLException.class, () -> executeAsWriter(
                "delete from audit.event where chain_id = '" + chain + "'")).getMessage().contains("permission denied"));
        assertTrue(assertThrows(SQLException.class, () -> executeAsWriter("truncate audit.event"))
                .getMessage().contains("permission denied"));
        assertTrue(assertThrows(SQLException.class, () -> executeAsWriter(
                "update audit.head_log set hash = hash where chain_id = '" + chain + "'"))
                .getMessage().contains("permission denied"));
    }

    private static PGSimpleDataSource writerDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(AuditTestDatabase.URL);
        ds.setUser(ROLE);
        ds.setPassword(PASSWORD);
        return ds;
    }

    private static void executeAsWriter(String sql) throws SQLException {
        try (Connection connection = writerDataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = AuditTestDatabase.connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
