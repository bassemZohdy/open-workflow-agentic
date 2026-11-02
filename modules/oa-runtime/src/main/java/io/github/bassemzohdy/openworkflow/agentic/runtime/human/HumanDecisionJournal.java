package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/**
 * Transaction primitive against an isolated proposed schema. Not wired to production: a committed row is neither
 * an audit receipt nor permission to wake a workflow. The caller owns the transaction and must roll back on any
 * exception. This primitive does not authenticate the actor, evaluate policy, verify the live workflow lease or
 * recheck caller authority at commit. Those are mandatory before an actual service may invoke it.
 */
final class HumanDecisionJournal {
    enum Result { RECORDED, REPLAYED }
    static final class Conflict extends RuntimeException {
        Conflict() { super("human decision conflicts with retained state"); }
    }

    private final Clock clock;
    HumanDecisionJournal(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    Result record(Connection connection, HumanDecision decision, Ulid instanceId, String waitKey) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(decision); Objects.requireNonNull(instanceId);
        if (waitKey == null || waitKey.isBlank() || waitKey.length() > 1024) throw new IllegalArgumentException("wait key required");
        CanonicalEncoder.strictUtf8(waitKey);
        if (connection.getAutoCommit()) throw new IllegalArgumentException("an existing transaction is required");
        if (alreadyRecorded(connection, decision, instanceId, waitKey)) return Result.REPLAYED;
        try (var q = connection.prepareStatement("select instance_id,wait_key,version,status,initiator_kind,initiator_id,"
                + "assignee_id,separation,due_at from human_proposal.task where tenant=? and task_id=? for update")) {
            q.setString(1, decision.tenant()); q.setString(2, decision.taskId().value());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new Conflict();
                // A concurrent exact retry can have committed while this transaction waited for the task lock.
                if (alreadyRecorded(connection, decision, instanceId, waitKey)) return Result.REPLAYED;
                if (!instanceId.value().equals(r.getString(1)) || !waitKey.equals(r.getString(2))
                        || decision.expectedVersion() != r.getLong(3) || !"IN_PROGRESS".equals(r.getString(4))
                        || !Set.of("USER", "AGENT", "WORKFLOW", "SERVICE").contains(r.getString(5))
                        || !Set.of("NONE", "INITIATOR_MUST_NOT_ACT").contains(r.getString(8))
                        || !decision.actor().id().equals(r.getString(7))
                        || ("INITIATOR_MUST_NOT_ACT".equals(r.getString(8)) && "USER".equals(r.getString(5))
                            && decision.actor().id().equals(r.getString(6)))
                        || (r.getTimestamp(9) != null && !clock.instant().isBefore(r.getTimestamp(9).toInstant()))) {
                    throw new Conflict();
                }
            }
        }
        try (var q = connection.prepareStatement("insert into human_proposal.decision "
                + "(tenant,decision_id,task_id,expected_version,actor_id,outcome,content,fingerprint,instance_id,wait_key) "
                + "values (?,?,?,?,?,?,?,?,?,?)")) {
            q.setString(1, decision.tenant()); q.setString(2, decision.id().value()); q.setString(3, decision.taskId().value());
            q.setLong(4, decision.expectedVersion()); q.setString(5, decision.actor().id()); q.setString(6, decision.outcome().name());
            q.setString(7, decision.content()); q.setString(8, decision.fingerprint()); q.setString(9, instanceId.value());
            q.setString(10, waitKey); q.executeUpdate();
        }
        try (var q = connection.prepareStatement("update human_proposal.task set status='COMPLETED',version=version+1 "
                + "where tenant=? and task_id=? and version=? and status='IN_PROGRESS'")) {
            q.setString(1, decision.tenant()); q.setString(2, decision.taskId().value()); q.setLong(3, decision.expectedVersion());
            if (q.executeUpdate() != 1) throw new Conflict();
        }
        try (var q = connection.prepareStatement("insert into human_proposal.pending "
                + "(tenant,decision_id,audit_state,wake_state) values (?,?,'PENDING','BLOCKED')")) {
            q.setString(1, decision.tenant()); q.setString(2, decision.id().value()); q.executeUpdate();
        }
        return Result.RECORDED;
    }

    private static boolean alreadyRecorded(Connection connection, HumanDecision decision, Ulid instanceId, String waitKey)
            throws SQLException {
        try (var q = connection.prepareStatement("select d.fingerprint,d.instance_id,d.wait_key,d.task_id,"
                + "d.expected_version,d.actor_id,d.outcome,d.content,t.status,t.version,p.audit_state,p.wake_state,"
                + "t.instance_id,t.wait_key,t.assignee_id "
                + "from human_proposal.decision d left join human_proposal.task t on t.tenant=d.tenant and t.task_id=d.task_id "
                + "left join human_proposal.pending p on p.tenant=d.tenant and p.decision_id=d.decision_id "
                + "where d.tenant=? and d.decision_id=?")) {
            q.setString(1, decision.tenant()); q.setString(2, decision.id().value());
            try (var r = q.executeQuery()) {
                if (!r.next()) return false;
                if (!decision.fingerprint().equals(r.getString(1)) || !instanceId.value().equals(r.getString(2))
                        || !waitKey.equals(r.getString(3)) || !decision.taskId().value().equals(r.getString(4))
                        || decision.expectedVersion() != r.getLong(5) || !decision.actor().id().equals(r.getString(6))
                        || !decision.outcome().name().equals(r.getString(7)) || !Objects.equals(decision.content(), r.getString(8))
                        || !"COMPLETED".equals(r.getString(9)) || r.getLong(10) != decision.expectedVersion() + 1
                        || !"PENDING".equals(r.getString(11)) || !"BLOCKED".equals(r.getString(12))
                        || !instanceId.value().equals(r.getString(13)) || !waitKey.equals(r.getString(14))
                        || !decision.actor().id().equals(r.getString(15))) throw new Conflict();
                return true;
            }
        }
    }
}
