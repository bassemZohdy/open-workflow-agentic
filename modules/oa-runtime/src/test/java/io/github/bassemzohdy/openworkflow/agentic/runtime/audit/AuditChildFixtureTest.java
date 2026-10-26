package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The parent append-only TRUNCATE probe must keep proving the PARENT guard once a child table holds a foreign key to
 * audit.source_binding (the shape of the proposed V77 effect.audit_delivery.ack_source_key). The private database
 * carries foundation 74 plus the pinned proposed V75 and ONE test-only child, audit_child_fixture.ack_probe. That
 * child only represents the V77 foreign-key shape: this is NOT coverage of the real V77 migration, which is not here.
 * Negative controls disable guards only inside a transaction that is always rolled back, only in this private,
 * UUID-named database, never in the shared default one.
 */
class AuditChildFixtureTest {

    private static AuditSourceTestDatabase db;

    @BeforeAll
    static void install() throws SQLException {
        db = AuditSourceTestDatabase.withProposedV75();
        db.execute(
                "create schema audit_child_fixture",
                // TEST-ONLY stand-in for a V77 incoming child of audit.source_binding; reuses the audit reject trigger
                "create table audit_child_fixture.ack_probe (ack_source_key text references audit.source_binding(source_key))",
                "create trigger ack_probe_no_change before update or delete on audit_child_fixture.ack_probe "
                        + "for each row execute function audit.reject_change()",
                "create trigger ack_probe_no_truncate before truncate on audit_child_fixture.ack_probe "
                        + "for each statement execute function audit.reject_change()");
    }

    @AfterAll
    static void drop() {
        db.close();
    }

    @Test
    void theOriginalListedProbeStopsEarlyOnceAnIncomingChildExists() throws SQLException {
        for (String parent : List.of("event", "head_log")) {
            try (Connection connection = db.dataSource().getConnection()) {
                SQLException error = assertThrows(SQLException.class, () -> AppendOnlyTest.executeRolledBack(
                        connection, "truncate audit." + parent + ", audit.source_binding"));
                assertEquals("0A000", error.getSQLState(), error.getMessage());
                assertFalse(error.getMessage().contains("append-only"), error.getMessage());
            }
        }
    }

    @Test
    void theCascadingProbeReachesTheEventParentGuard() throws SQLException {
        assertParentGuardNamed("event");
    }

    @Test
    void theCascadingProbeReachesTheHeadLogParentGuard() throws SQLException {
        assertParentGuardNamed("head_log");
    }

    @Test
    void withoutTheEventParentGuardNoChildGuardSatisfiesTheParentAssertion() throws SQLException {
        assertNoChildSatisfiesParent("event");
    }

    @Test
    void withoutTheHeadLogParentGuardNoChildGuardSatisfiesTheParentAssertion() throws SQLException {
        assertNoChildSatisfiesParent("head_log");
    }

    @Test
    void withoutParentAndSourceBindingGuardsTheCascadeStillReachesTheFixtureChild() throws SQLException {
        for (String parent : List.of("event", "head_log")) {
            SQLException error = disabledProbe(parent, List.of("audit.source_binding"));
            AppendOnlyTest.assertRejected(error);
            assertTrue(error.getMessage().contains("audit table ack_probe is append-only"), error.getMessage());
        }
    }

    @Test
    void everyGuardIsEnabledAgainAfterTheControls() throws SQLException {
        assertNoChildSatisfiesParent("event");
        try (Connection connection = db.dataSource().getConnection(); Statement s = connection.createStatement();
             var rs = s.executeQuery("select count(*) from pg_trigger where not tgisinternal and tgenabled <> 'O'")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    private static void assertParentGuardNamed(String parent) throws SQLException {
        try (Connection connection = db.dataSource().getConnection()) {
            SQLException error = assertThrows(SQLException.class, () -> AppendOnlyTest.executeRolledBack(
                    connection, AppendOnlyTest.truncate(parent)));
            AppendOnlyTest.assertRejected(error);
            AppendOnlyTest.assertNamesTable(error, parent);
        }
    }

    /** With the parent guard off the error is a descendant's: still append-only, but the parent assertion must fail. */
    private static void assertNoChildSatisfiesParent(String parent) throws SQLException {
        SQLException error = disabledProbe(parent, List.of());
        AppendOnlyTest.assertRejected(error);
        assertThrows(AssertionError.class, () -> AppendOnlyTest.assertNamesTable(error, parent));
    }

    /** Disables the truncate guards of the parent and the given tables, runs the cascading probe, always rolls back. */
    private static SQLException disabledProbe(String parent, List<String> alsoDisabled) throws SQLException {
        try (Connection connection = db.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                List<String> tables = new ArrayList<>(alsoDisabled);
                tables.add("audit." + parent);
                for (String table : tables) {
                    disableTruncateGuards(s, table);
                }
                return assertThrows(SQLException.class, () -> s.execute(AppendOnlyTest.truncate(parent)));
            } finally {
                connection.rollback();
            }
        }
    }

    private static void disableTruncateGuards(Statement s, String table) throws SQLException {
        List<String> triggers = new ArrayList<>();
        try (var rs = s.executeQuery("select tgname from pg_trigger where tgrelid = '" + table
                + "'::regclass and not tgisinternal and (tgtype & 32) = 32")) {
            while (rs.next()) {
                triggers.add(rs.getString(1));
            }
        }
        assertEquals(1, triggers.size(), "exactly one truncate guard on " + table);
        s.execute("alter table " + table + " disable trigger " + triggers.get(0));
    }
}
