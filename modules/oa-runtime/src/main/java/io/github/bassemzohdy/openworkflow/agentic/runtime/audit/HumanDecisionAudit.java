package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditDraftFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/** Transaction-local audit for the isolated human-decision proposal; never commits the caller's connection. */
public final class HumanDecisionAudit {
    private static final String CHAIN = "human";

    private HumanDecisionAudit() { }

    private static AuditEvent draft(HumanDecision decision, Ulid instance, Instant occurredAt) {
        return AuditDraft.of(decision.id(), occurredAt, decision.actor(), "human.decision.recorded", "human-task",
                decision.taskId().value(), Map.of(AuditKeys.CHAIN, CHAIN, AuditKeys.TENANT, decision.tenant(),
                        AuditKeys.INSTANCE, instance.value(), "human.expected-version", Long.toString(decision.expectedVersion()),
                        "human.outcome", decision.outcome().name()));
    }

    /** Appends a non-sensitive transition fact in the supplied transaction, without committing it. */
    public static void append(Connection connection, HumanDecision decision, Ulid instance, Instant occurredAt)
            throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(decision); Objects.requireNonNull(instance);
        Objects.requireNonNull(occurredAt);
        if (connection.getAutoCommit()) throw new IllegalArgumentException("an existing transaction is required");
        if (!occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("audit time must be microsecond-exact");
        }
        AuditTrail.appendInTransaction(connection, CHAIN, draft(decision, instance, occurredAt));
    }

    /** Checks the stored event and its retained head entry, not the entire chain or current authority. */
    public static boolean matches(Connection connection, HumanDecision decision, Ulid instance, Instant occurredAt)
            throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(decision); Objects.requireNonNull(instance);
        Objects.requireNonNull(occurredAt);
        AuditEvent event;
        try (var q = connection.prepareStatement("select " + AuditRows.EVENT_COLUMNS + " from audit.event where event_id=?")) {
            q.setString(1, decision.id().value());
            try (var r = q.executeQuery()) {
                if (!r.next()) return false;
                event = AuditRows.event(r);
            }
        }
        if (!AuditDraftFingerprint.of(draft(decision, instance, occurredAt)).equals(AuditDraftFingerprint.of(event))
                || !AuditHash.compute(event).equals(event.hash())) return false;
        try (var q = connection.prepareStatement("select hash from audit.head_log where chain_id=? and seq=?")) {
            q.setString(1, CHAIN); q.setLong(2, event.sequence());
            try (var r = q.executeQuery()) { return r.next() && event.hash().equals(r.getString(1)); }
        }
    }
}
