package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Appends a checkpoint-recovery audit in the caller's transaction; never commits it. */
public final class CheckpointRecoveryAudit {
    private CheckpointRecoveryAudit() { }
    public static void append(Connection connection, AuditEvent event) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(event);
        if (connection.getAutoCommit()) throw new IllegalArgumentException("an existing transaction is required");
        if (!"event.checkpoint.observed".equals(event.action()) || !"event-delivery".equals(event.resourceType()))
            throw new IllegalArgumentException("not a checkpoint recovery audit");
        AuditTrail.appendInTransaction(connection, AuditKeys.chainOf(event.attributes()), event);
    }
}
