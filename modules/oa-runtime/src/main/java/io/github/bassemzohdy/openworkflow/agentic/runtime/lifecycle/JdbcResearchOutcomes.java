package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResearchOutputFingerprint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector;
import jakarta.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Explicit co-located checkpoint association. The target fingerprint must come from trusted database configuration,
 * not a request. A verified association is historical data, never permission to re-enter or release private output.
 * Checkpoint rows may still change after any read: every output consumer must revalidate the exact stored bytes and
 * live authority. No Java checkpoint content is deserialized here.
 */
public final class JdbcResearchOutcomes {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private final DataSource data;
    private final TransactionManager transactions;
    private final String targetFingerprint;
    private final ResearchRunInspector inspector;

    public JdbcResearchOutcomes(DataSource data, TransactionManager transactions, String targetFingerprint) {
        this.data = Objects.requireNonNull(data); this.transactions = Objects.requireNonNull(transactions);
        if (targetFingerprint == null || !HASH.matcher(targetFingerprint).matches())
            throw new IllegalArgumentException("invalid checkpoint target fingerprint");
        this.targetFingerprint = targetFingerprint;
        this.inspector = new ResearchRunInspector(data);
    }

    /** Retains the exact released terminal checkpoint, or refuses missing, partial, repeated or changed evidence. */
    public ResourceExecutionOutcome associate(ResourceExecutionEntry entry) {
        requireTarget(entry);
        return suspended(() -> {
            var observed = terminal(entry);
            var outcome = new ResourceExecutionOutcome(entry, ResearchOutputFingerprint.PROFILE,
                    UUID.fromString(observed.runId()), UUID.fromString(observed.checkpointId()),
                    observed.finalState().contentType(), observed.finalState().fingerprint());
            transact(c -> {
                requireEntry(c, entry);
                try (var q = c.prepareStatement("""
                        insert into task.resource_execution_outcome
                          (entry_key,profile,target_fingerprint,run_id,checkpoint_id,content_type,content_fingerprint)
                        values (?,?,?,?,?,?,?) on conflict(entry_key) do nothing
                        """)) {
                    q.setString(1, entry.fingerprint()); q.setString(2, outcome.profile());
                    q.setString(3, targetFingerprint); q.setObject(4, outcome.runId());
                    q.setObject(5, outcome.checkpointId()); q.setString(6, outcome.contentType());
                    q.setString(7, outcome.contentFingerprint()); q.executeUpdate();
                }
                if (!outcome.equals(read(c, entry).orElseThrow())) throw conflict();
                return null;
            });
            requireObserved(outcome, terminal(entry));
            return outcome;
        });
    }

    /** Retained association only, including after authority loss. It does not establish current checkpoint content. */
    public Optional<ResourceExecutionOutcome> find(ResourceExecutionEntry entry) {
        requireTarget(entry);
        return suspended(() -> transact(c -> {
            requireEntry(c, entry);
            return read(c, entry);
        }));
    }

    /** Rechecks exact current terminal data; it does not authorize output or establish checkpoint immutability. */
    public ResourceExecutionOutcome verify(ResourceExecutionEntry entry) {
        requireTarget(entry);
        return suspended(() -> {
            var outcome = transact(c -> { requireEntry(c, entry); return read(c, entry).orElseThrow(JdbcResearchOutcomes::conflict); });
            requireObserved(outcome, terminal(entry));
            return outcome;
        });
    }

    private void requireTarget(ResourceExecutionEntry entry) {
        Objects.requireNonNull(entry);
        if (!targetFingerprint.equals(entry.scope().targetFingerprint())) throw conflict();
    }

    private ResearchRunInspector.Inspection terminal(ResourceExecutionEntry entry) {
        var observed = inspector.inspect(entry.threadId().value());
        if (observed.kind() != ResearchRunInspector.Kind.COMPLETE || !observed.released()
                || observed.finalState() == null || observed.runId() == null || observed.checkpointId() == null)
            throw conflict();
        return observed;
    }

    private static void requireObserved(ResourceExecutionOutcome outcome, ResearchRunInspector.Inspection observed) {
        try {
            outcome.requireMatches(outcome.entry(), outcome.entry().threadId(), UUID.fromString(observed.runId()),
                    UUID.fromString(observed.checkpointId()), observed.finalState().contentType(), observed.finalState().base64());
        } catch (IllegalArgumentException | NullPointerException mismatch) {
            throw conflict();
        }
    }

    private static void requireEntry(Connection c, ResourceExecutionEntry entry) throws SQLException {
        var retained = JdbcResourceExecutionGate.read(c, entry.scope()).orElseThrow(JdbcResearchOutcomes::conflict);
        if (!retained.entry().equals(entry)) throw conflict();
    }

    private Optional<ResourceExecutionOutcome> read(Connection c, ResourceExecutionEntry entry) throws SQLException {
        try (var q = c.prepareStatement("""
                select profile,target_fingerprint,run_id,checkpoint_id,content_type,content_fingerprint
                from task.resource_execution_outcome where entry_key=?
                """)) {
            q.setString(1, entry.fingerprint());
            try (var r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                if (!targetFingerprint.equals(r.getString(2))) throw conflict();
                var value = new ResourceExecutionOutcome(entry, r.getString(1), r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class), r.getString(5), r.getString(6));
                if (r.next()) throw conflict();
                return Optional.of(value);
            }
        }
    }

    private <T> T transact(Transactions.Work<T> work) {
        return Transactions.inBoundedPostgresTransaction(data, work, 2000, 5000, 5000);
    }
    private <T> T suspended(Supplier<T> work) { return Transactions.inSuspendedTransaction(transactions, work); }
    private static IllegalStateException conflict() { return new IllegalStateException("research outcome evidence is unavailable or changed"); }
}
