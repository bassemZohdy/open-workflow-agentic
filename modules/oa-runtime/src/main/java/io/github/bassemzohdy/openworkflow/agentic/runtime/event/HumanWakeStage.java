package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.HumanDecisionAudit;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Opt-in isolated-schema proposal: atomically retain a reference event, bind it to one wait under a task claim and
 * retain a staged marker. No CDI or endpoint wiring. A staged marker is not a callback reservation, a current lease,
 * a verified human authorization, an SDK predicate proof or a checkpoint receipt. Production adoption needs all of
 * those boundaries, an approved producer, a real schema and target-specific callback delivery.
 */
public final class HumanWakeStage {
    public enum Result { STAGED, ALREADY_STAGED }
    private final DataSource dataSource;
    private final JdbcEventInbox inbox;
    private final JdbcOwnedInbox owned;
    private final Clock clock;

    public HumanWakeStage(DataSource dataSource, JdbcEventInbox inbox, JdbcOwnedInbox owned, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource); this.inbox = Objects.requireNonNull(inbox);
        this.owned = Objects.requireNonNull(owned); this.clock = Objects.requireNonNull(clock);
    }

    /** Only one literal data filter is supported; all other workflow shapes fail closed. */
    public Result stage(HumanDecision decision, InboxWait wait, InboxTaskClaim claim, InboxListenGroup group, String snapshot) {
        Objects.requireNonNull(decision); Objects.requireNonNull(wait); Objects.requireNonNull(claim);
        Objects.requireNonNull(group); Objects.requireNonNull(snapshot);
        if (!claim.task().equals(wait.task()) || group.strategy() != InboxListenGroup.Strategy.ONE
                || group.stop() != InboxListenGroup.Stop.NONE || group.dataFilters().size() != 1
                || !group.dataFilters().getFirst().equals(wait.filterDigest())) {
            throw new IllegalArgumentException("human wake must identify one pinned task and filter");
        }
        InboxMessage message = HumanDecisionWake.message(decision, wait);
        if (HumanWakeSelection.select(decision, wait, group, snapshot, message).isEmpty()) {
            throw new IllegalArgumentException("human wake does not match the pinned literal filter");
        }
        return Transactions.inTransaction(dataSource, c -> {
            // The receipt, exact signal and marker are invisible until they commit together.
            inbox.receiveInTransaction(c, message, clock.instant().plus(Duration.ofDays(29)));
            if (owned.matchInTransaction(c, claim, wait, message).filter(message::equals).isEmpty()) {
                throw new IllegalStateException("the owned wait cannot bind this receipt");
            }
            String state = requireDecision(c, decision, wait);
            if ("STAGED".equals(state)) {
                requireExistingStage(c, decision, wait, message);
                requireMatched(c, claim, wait, message);
                return Result.ALREADY_STAGED;
            }
            if (!"BLOCKED".equals(state)) throw new IllegalStateException("human wake has invalid state");
            try (var q = c.prepareStatement("insert into human_proposal.wake_stage "
                    + "(tenant,decision_id,instance_id,wait_key,source,event_id,receipt_fingerprint,task_digest,claim_owner,claim_epoch) "
                    + "values (?,?,?,?,?,?,?,?,?,?)")) {
                q.setString(1, decision.tenant()); q.setString(2, decision.id().value()); q.setString(3, wait.instanceId().value());
                q.setString(4, wait.key()); q.setString(5, message.source()); q.setString(6, message.eventId());
                q.setString(7, message.fingerprint()); q.setString(8, claim.task().digest()); q.setString(9, claim.owner());
                q.setLong(10, claim.epoch()); q.executeUpdate();
            }
            try (var q = c.prepareStatement("update human_proposal.pending set wake_state='STAGED' "
                    + "where tenant=? and decision_id=? and wake_state='BLOCKED'")) {
                q.setString(1, decision.tenant()); q.setString(2, decision.id().value());
                if (q.executeUpdate() != 1) throw new IllegalStateException("human wake state changed");
            }
            requireMatched(c, claim, wait, message);
            return Result.STAGED;
        });
    }

    private void requireMatched(Connection c, InboxTaskClaim claim, InboxWait wait, InboxMessage message) throws SQLException {
        if (owned.matchInTransaction(c, claim, wait, message).filter(message::equals).isEmpty()) {
            throw new IllegalStateException("the owned wait lost its receipt");
        }
    }

    private static String requireDecision(Connection c, HumanDecision decision, InboxWait wait) throws SQLException {
        try (var q = c.prepareStatement("select d.task_id,d.expected_version,d.actor_id,d.outcome,d.content,d.fingerprint,"
                + "d.instance_id,d.wait_key,t.status,t.version,t.assignee_id,p.audit_state,p.wake_state,p.audit_event_id,p.audit_occurred_at,"
                + "t.instance_id,t.wait_key "
                + "from human_proposal.decision d join human_proposal.task t on t.tenant=d.tenant and t.task_id=d.task_id "
                + "join human_proposal.pending p on p.tenant=d.tenant and p.decision_id=d.decision_id "
                + "where d.tenant=? and d.decision_id=? for update of t,p")) {
            q.setString(1, decision.tenant()); q.setString(2, decision.id().value());
            try (var r = q.executeQuery()) {
                if (!r.next() || !decision.taskId().value().equals(r.getString(1)) || decision.expectedVersion()!=r.getLong(2)
                        || !decision.actor().id().equals(r.getString(3)) || !decision.outcome().name().equals(r.getString(4))
                        || !Objects.equals(decision.content(),r.getString(5)) || !decision.fingerprint().equals(r.getString(6))
                        || !wait.instanceId().value().equals(r.getString(7)) || !wait.key().equals(r.getString(8))
                        || !"COMPLETED".equals(r.getString(9)) || r.getLong(10)!=decision.expectedVersion()+1
                        || !decision.actor().id().equals(r.getString(11)) || !"RECORDED".equals(r.getString(12))
                        || !decision.id().value().equals(r.getString(14)) || r.getTimestamp(15)==null
                        || !wait.instanceId().value().equals(r.getString(16)) || !wait.key().equals(r.getString(17))
                        || !HumanDecisionAudit.matches(c,decision,wait.instanceId(),r.getTimestamp(15).toInstant())) {
                    throw new IllegalStateException("retained human decision or audit differs");
                }
                return r.getString(13);
            }
        }
    }

    private static void requireExistingStage(Connection c, HumanDecision decision, InboxWait wait, InboxMessage message)
            throws SQLException {
        try (var q = c.prepareStatement("select instance_id,wait_key,source,event_id,receipt_fingerprint,task_digest "
                + "from human_proposal.wake_stage where tenant=? and decision_id=?")) {
            q.setString(1,decision.tenant()); q.setString(2,decision.id().value());
            try (var r=q.executeQuery()) {
                if (!r.next() || !wait.instanceId().value().equals(r.getString(1)) || !wait.key().equals(r.getString(2))
                        || !message.source().equals(r.getString(3)) || !message.eventId().equals(r.getString(4))
                        || !message.fingerprint().equals(r.getString(5)) || !wait.task().digest().equals(r.getString(6))) {
                    throw new IllegalStateException("retained human wake differs");
                }
            }
        }
    }
}
