package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Simulates an attacker who has owner rights and disables the append-only triggers, then changes the audit tables.
 * The triggers are enabled again in {@code finally}, so a failing test cannot leave the database unprotected.
 */
final class Tamper {

    private static final List<String> TRIGGERS = List.of(
            "audit.event event_append_only_update",
            "audit.event event_append_only_delete",
            "audit.head_log head_log_append_only_update",
            "audit.head_log head_log_append_only_delete");

    private Tamper() {
    }

    /** Runs {@code statements} in one transaction with the append-only triggers disabled. */
    static void run(String... statements) throws SQLException {
        try (Connection connection = AuditTestDatabase.connection()) {
            setTriggers(connection, "disable");
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (String sql : statements) {
                        statement.execute(sql);
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
                setTriggers(connection, "enable");
            }
        }
    }

    private static void setTriggers(Connection connection, String action) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String trigger : TRIGGERS) {
                String[] parts = trigger.split(" ");
                statement.execute("alter table " + parts[0] + " " + action + " trigger " + parts[1]);
            }
        }
    }
}
