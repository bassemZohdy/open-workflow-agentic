package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import jakarta.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Explicit candidate task-and-target check for one entry and original durable allocation. The caller must supply a
 * trusted current approval check covering the full allocation, task provenance and target; retained
 * allocation data alone is not approval. V78 is proposed and this is not installed in application startup.
 *
 * <p>Database time, the complete allocation, exact credential binding and its actual grant ancestry are checked
 * before and after potentially blocking approval I/O.
 * A check can become stale immediately afterward and cannot stop an external effect already in flight.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class ResourceTaskExecutionFence implements ResourceExecutionFence, AutoCloseable {
    @FunctionalInterface public interface CurrentApproval {
        void requireCurrent(ResourceExecutionEntry entry, AllocatedResourceTask allocation);
    }

    private final DataSource data;
    private final TransactionManager transactions;
    private final ResourceExecutionEntry entry;
    private final AllocatedResourceTask expected;
    private final ResourceCallBinding binding;
    private final CurrentApproval approval;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResourceTaskExecutionFence(DataSource data, TransactionManager transactions, ResourceExecutionEntry entry,
                                      AllocatedResourceTask expected, ResourceCallBinding binding, CurrentApproval approval) {
        this.data = Objects.requireNonNull(data, "data");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.expected = Objects.requireNonNull(expected, "expected");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.approval = Objects.requireNonNull(approval, "approval");
        if (!entry.scope().equals(expected.allocation().scope()) || !entry.scope().equals(binding.scope()))
            throw new IllegalArgumentException("task scope differs from entry");
    }

    /** Full-data comparison for trusted identity/task pairing, not an authorization decision. */
    public boolean boundTo(ResourceExecutionEntry candidate, ResourceCallBinding credential) {
        return !closed.get() && entry.equals(candidate) && binding.equals(credential);
    }

    @Override public void requireCurrent(ResourceExecutionEntry requested) {
        requireOpen();
        if (!entry.equals(requested)) throw refused("entry differs");
        try {
            requireAllocation();
            approval.requireCurrent(entry, expected);
            requireOpen();
            requireAllocation(); // Approval can block beyond the retained database deadline.
            requireOpen();
        } catch (JdbcResourceExecutionGate.RefusedException refused) {
            throw refused;
        } catch (RuntimeException failure) {
            var refused = refused("task or target check unavailable");
            refused.initCause(failure);
            throw refused;
        }
    }

    private void requireAllocation() {
        Transactions.inSuspendedTransaction(transactions, () -> Transactions.inTransaction(data, c -> {
            Transactions.capPostgresTimeouts(c, 2_000, 5_000, 5_000);
            var current = JdbcResourceTaskAllocations.read(c, entry.scope()).orElseThrow(() -> refused("task missing"));
            if (!expected.equals(current)) throw refused("task differs");
            var retained = JdbcResourceCallBindings.read(c, binding.issuer(), binding.tokenId())
                    .orElseThrow(() -> refused("credential missing"));
            if (!binding.equals(retained)) throw refused("credential differs");
            var ancestry = ancestry(c);
            var parent = parent(c);
            try (var q = c.prepareStatement("select pg_catalog.clock_timestamp()")) {
                q.setQueryTimeout(5);
                try (var r = q.executeQuery()) {
                    if (!r.next()) throw refused("database clock unavailable");
                    Instant now = r.getTimestamp(1).toInstant();
                    if (!now.isBefore(current.deadline()) || !now.isBefore(binding.expiresAt())
                            || binding.issuedAt().isAfter(now)) throw refused("task or credential deadline passed");
                    if (ancestry.isEmpty() || ancestry.get(ancestry.size()-1).parent() != null
                            || ancestry.stream().noneMatch(grant -> grant.id().equals(binding.issuingGrantId().value())))
                        throw refused("credential grant is not in task ancestry");
                    var scope = entry.scope();
                    for (var grant : ancestry) {
                        if (!grant.tenant().equals(scope.tenant()) || !grant.subjectKind().equals(scope.owner().kind().name())
                                || !grant.subjectId().equals(scope.owner().id()) || !grant.active()
                                || !now.isBefore(grant.expiresAt())) throw refused("task grant unavailable");
                    }
                    var leaf = ancestry.get(0);
                    var agent = current.allocation().modelProfile().agent();
                    if (!leaf.agentKind().equals(agent.kind().name()) || !leaf.agentId().equals(agent.id()))
                        throw refused("task grant agent differs");
                    if (parent != null) requireCurrentParent(parent, ancestry, now);
                }
            }
            return null;
        }, 6_000));
    }

    private record ParentRow(String instance, String tenant, String ownerKind, String ownerId, String thread,
                             String status, Instant createdAt, AgentTaskContract contract) { }
    private ParentRow parent(Connection c) throws SQLException {
        var parentId = expected.allocation().parentTaskId();
        if (parentId == null) return null;
        try (var q = c.prepareStatement("""
                select instance_id,tenant,owner_kind,owner_id,thread_name,status,created_at,
                       case when octet_length(contract::text)<=65536 then contract::text end
                  from task.agent_task where id=?
                """)) {
            q.setQueryTimeout(5);
            q.setString(1, parentId.value());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw refused("parent task missing");
                var value = new ParentRow(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getTimestamp(7).toInstant(),
                        ResourceTaskAllocationCodec.contract(r.getString(8)));
                if (r.next()) throw refused("parent task is inconsistent");
                return value;
            }
        }
    }

    private void requireCurrentParent(ParentRow parent, java.util.List<GrantRow> ancestry, Instant now) {
        var scope = entry.scope();
        var child = expected.allocation().contract();
        var contract = parent.contract();
        if (!scope.instanceId().value().equals(parent.instance()) || !scope.tenant().equals(parent.tenant())
                || !scope.owner().kind().name().equals(parent.ownerKind()) || !scope.owner().id().equals(parent.ownerId())
                || !ThreadId.derive(scope.tenant(), scope.owner().id(), scope.instanceId().value()).value().equals(parent.thread())
                || !expected.allocation().parentTaskId().equals(contract.id())
                || !scope.owner().equals(contract.requestingSubject())
                || !Set.of("PENDING", "RUNNING", "WAITING_FOR_HUMAN", "RESUMING").contains(parent.status())
                || parent.createdAt().isAfter(now) || !now.isBefore(parent.createdAt().plus(contract.limits().maxWallClock()))
                || expected.deadline().isAfter(parent.createdAt().plus(contract.limits().maxWallClock()))
                || !contract.permittedTools().containsAll(child.permittedTools())
                || !contract.permittedDataScopes().containsAll(child.permittedDataScopes())
                || !child.riskTier().atLeast(contract.riskTier()) || !child.limits().isWithin(contract.limits())
                || ancestry.stream().noneMatch(g -> g.id().equals(contract.delegationGrantId().value())))
            throw refused("parent task is closed or differs");
    }

    private record GrantRow(String id, String parent, String tenant, String subjectKind, String subjectId,
                            String agentKind, String agentId, boolean active, Instant expiresAt) { }
    private java.util.List<GrantRow> ancestry(Connection c) throws SQLException {
        var grants = new ArrayList<GrantRow>();
        try (var q = c.prepareStatement("""
                with recursive ancestry as (
                    select id,parent_grant_id,tenant,subject_kind,subject_id,agent_kind,agent_id,status,expires_at,
                           array[id] as path,1 as depth
                      from identity.delegation_grant where id=?
                    union all
                    select g.id,g.parent_grant_id,g.tenant,g.subject_kind,g.subject_id,g.agent_kind,g.agent_id,
                           g.status,g.expires_at,a.path || g.id,a.depth+1
                      from identity.delegation_grant g join ancestry a on g.id=a.parent_grant_id
                     where a.depth<8 and not g.id=any(a.path)
                ) select id,parent_grant_id,tenant,subject_kind,subject_id,agent_kind,agent_id,status,expires_at
                    from ancestry order by depth
                """)) {
            q.setQueryTimeout(5);
            q.setString(1, expected.allocation().contract().delegationGrantId().value());
            try (var rows = q.executeQuery()) {
                while (rows.next()) grants.add(new GrantRow(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7),
                        "ACTIVE".equals(rows.getString(8)), rows.getTimestamp(9).toInstant()));
            }
        }
        return grants;
    }

    private void requireOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw refused("task context unavailable");
    }
    private static JdbcResourceExecutionGate.RefusedException refused(String reason) {
        return new JdbcResourceExecutionGate.RefusedException(reason);
    }
    @Override public void close() { closed.set(true); }
    @Override public String toString() { return "ResourceTaskExecutionFence[redacted]"; }
}
