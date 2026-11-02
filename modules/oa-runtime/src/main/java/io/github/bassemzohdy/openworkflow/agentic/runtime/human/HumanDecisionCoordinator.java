package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.HumanAction;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.HumanDecisionAdmission;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Isolated-schema prototype for a NEW decision. No endpoint or CDI wiring. The required fence has no production
 * implementation here: it must lock and validate the real wait and its current lease inside this transaction, with
 * an expiry check enforced at commit rather than merely before a potentially blocking commit. A commit exception is
 * uncertain, and this class deliberately does not authorize replay or release a workflow.
 */
final class HumanDecisionCoordinator {
    @FunctionalInterface interface WaitFence {
        /** Check the exact wait and its live lease with transaction-held database locks; never make a remote call. */
        void requireCurrent(Connection connection, String tenant, Ulid instance, String waitKey) throws SQLException;
    }
    static final class Refused extends RuntimeException { Refused() { super("human decision refused"); } }
    static final class Unavailable extends RuntimeException { Unavailable(Throwable cause) { super("human decision unavailable", cause); } }
    static final class CommitUncertain extends RuntimeException { CommitUncertain(Throwable cause) { super("human decision commit is uncertain", cause); } }
    private record Snapshot(HumanActionFacts facts, Ulid instance, String waitKey, PolicyRef policy) { }

    private final DataSource dataSource;
    private final HumanDecisionAdmission admission;
    private final HumanDecisionJournal journal;
    private final WaitFence fence;
    private final Clock clock;

    HumanDecisionCoordinator(DataSource dataSource, HumanDecisionAdmission admission, WaitFence fence, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource); this.admission = Objects.requireNonNull(admission);
        this.fence = Objects.requireNonNull(fence); this.clock = Objects.requireNonNull(clock);
        this.journal = new HumanDecisionJournal(clock);
    }

    /** A successful return means the transaction committed; it does not authorize delivery or a replay read. */
    void recordNew(CallerAuthority authority, HumanDecision decision) {
        Objects.requireNonNull(authority); Objects.requireNonNull(decision);
        if (authority.delegated() || !decision.actor().equals(authority.caller().subject())
                || !decision.tenant().equals(authority.caller().tenant())) throw new Refused();
        authority.requireCurrent(Set.of(HumanAction.DECIDE.requiredScope()));
        Snapshot before;
        try (var connection = dataSource.getConnection()) {
            before = snapshot(connection, decision, false);
        } catch (SQLException e) { throw new Unavailable(e); }
        Decision policy = admission.check(authority, decision, before.facts());
        if (!policy.allows() || !before.policy().equals(policy.policy())) throw new Refused();
        boolean commitStarted = false;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Snapshot locked = snapshot(connection, decision, true);
                if (!before.equals(locked)) throw new Refused();
                fence.requireCurrent(connection, decision.tenant(), locked.instance(), locked.waitKey());
                if (journal.record(connection, decision, locked.instance(), locked.waitKey()) != HumanDecisionJournal.Result.RECORDED) {
                    throw new Refused();
                }
                // The fence must still hold after audit I/O, and a token or task that expired during it cannot commit.
                fence.requireCurrent(connection, decision.tenant(), locked.instance(), locked.waitKey());
                authority.requireCurrent(Set.of(HumanAction.DECIDE.requiredScope()));
                if (locked.facts().dueAt() != null && !clock.instant().isBefore(locked.facts().dueAt())) throw new Refused();
                commitStarted = true;
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (SQLException e) {
            if (commitStarted) throw new CommitUncertain(e);
            throw new Unavailable(e);
        } catch (RuntimeException e) {
            if (commitStarted) throw new CommitUncertain(e);
            throw e;
        }
    }

    private static Snapshot snapshot(Connection connection, HumanDecision decision, boolean lock) throws SQLException {
        String sql = "select instance_id,wait_key,version,status,initiator_kind,initiator_id,assignee_id,separation,"
                + "risk_tier,policy_id,policy_version,due_at from human_proposal.task where tenant=? and task_id=?"
                + (lock ? " for update" : "");
        try (var q = connection.prepareStatement(sql)) {
            q.setString(1, decision.tenant()); q.setString(2, decision.taskId().value());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new Refused();
                var due = r.getTimestamp(12);
                var facts = new HumanActionFacts(decision.tenant(), decision.taskId(), r.getLong(3),
                        HumanTaskStatus.valueOf(r.getString(4)), due == null ? null : due.toInstant(), null,
                        new Subject(SubjectKind.valueOf(r.getString(5)), r.getString(6)),
                        new Subject(SubjectKind.USER, r.getString(7)), RiskTier.valueOf(r.getString(9)),
                        HumanActionFacts.SeparationObligation.valueOf(r.getString(8)));
                return new Snapshot(facts, Ulid.parse(r.getString(1)), r.getString(2),
                        new PolicyRef(r.getString(10), r.getString(11)));
            }
        }
    }
}
