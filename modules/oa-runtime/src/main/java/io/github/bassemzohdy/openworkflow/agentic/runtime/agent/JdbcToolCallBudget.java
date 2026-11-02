package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ApprovedToolProfile;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.transaction.TransactionManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import javax.sql.DataSource;

/** Independently committed tool-attempt admissions bound to a pre-existing task budget and its deadline. */
public final class JdbcToolCallBudget {
    private final DataSource dataSource;
    private final TransactionManager transactions;

    public JdbcToolCallBudget(DataSource dataSource, TransactionManager transactions) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.transactions = Objects.requireNonNull(transactions);
    }

    /** For a non-enlisted datasource in standalone tests. */
    public JdbcToolCallBudget(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.transactions = null;
    }

    private <T> T transact(Transactions.Work<T> work) {
        java.util.function.Supplier<T> execute = () -> Transactions.inBoundedPostgresTransaction(dataSource, work, 2000, 5000, 5000);
        return transactions == null ? execute.get() : Transactions.inSuspendedTransaction(transactions, execute);
    }

    /**
     * Trusted preparation only: the caller must establish a genuinely fresh task and approved tool-call ceiling.
     * The presence or absence of a row is not proof of freshness; no production task-start producer is wired here.
     * Exact retries retain the existing count; different ceilings or task bindings are refused.
     */
    public GovernedToolGateway.ToolBudget registerForFreshTask(String thread, Subject actor, AgentTaskContract task,
                                                               int maxCalls) {
        validateCeiling(maxCalls);
        return open(thread, actor, task, maxCalls);
    }

    /** Insert only in the owned transaction that just created this allocation and its node budget. */
    public static void insertForFreshAllocation(Connection connection, AllocatedResourceTask allocated, int maxCalls)
            throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(allocated); validateCeiling(maxCalls);
        if (connection.getAutoCommit()) throw new IllegalArgumentException("fresh allocation requires an owned transaction");
        var allocation = allocated.allocation();
        String binding = JdbcNodeBudget.binding(allocation.scope().threadId().value(), allocation.modelProfile().agent(),
                allocation.contract());
        try (var q = connection.prepareStatement("""
                insert into task.agent_tool_budget(task_id,max_calls)
                select task_id,? from task.agent_node_budget
                where task_id=? and binding=? and started_at=? and deadline=?
                """)) {
            q.setInt(1, maxCalls); q.setString(2, allocation.contract().id().value()); q.setString(3, binding);
            q.setTimestamp(4, Timestamp.from(allocated.createdAt())); q.setTimestamp(5, Timestamp.from(allocated.deadline()));
            if (q.executeUpdate() != 1) throw new IllegalStateException("fresh allocation tool budget binding mismatch");
        }
    }

    /** Exact historical data check only; it neither renews an expired deadline nor authorizes execution. */
    public static void requireForAllocation(Connection connection, AllocatedResourceTask allocated, int maxCalls)
            throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(allocated); validateCeiling(maxCalls);
        var allocation = allocated.allocation();
        String binding = JdbcNodeBudget.binding(allocation.scope().threadId().value(), allocation.modelProfile().agent(),
                allocation.contract());
        try (var q = connection.prepareStatement("""
                select 1 from task.agent_tool_budget t join task.agent_node_budget n using (task_id)
                where t.task_id=? and t.max_calls=? and n.binding=? and n.started_at=? and n.deadline=?
                """)) {
            q.setString(1, allocation.contract().id().value()); q.setInt(2, maxCalls); q.setString(3, binding);
            q.setTimestamp(4, Timestamp.from(allocated.createdAt())); q.setTimestamp(5, Timestamp.from(allocated.deadline()));
            try (var rows = q.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("allocation tool budget is missing or mismatched");
            }
        }
    }

    private static void validateCeiling(int maxCalls) {
        if (maxCalls < 1 || maxCalls > 100_000) throw new IllegalArgumentException("invalid tool-call ceiling");
    }

    /** Retains profile data only in the transaction creating its fresh allocation and budget. */
    public static void insertProfileForFreshAllocation(Connection connection, AllocatedResourceTask allocated,
                                                        ApprovedToolProfile profile) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(allocated); Objects.requireNonNull(profile);
        if (connection.getAutoCommit()) throw new IllegalArgumentException("fresh allocation requires an owned transaction");
        profile.requireWithin(allocated.allocation().contract(), allocated.allocation().modelProfile().agent());
        try (var q = connection.prepareStatement("""
                insert into task.agent_tool_profile(task_id,profile_id,profile_version,configuration_fingerprint,
                    agent_id,tools,max_calls,max_call_nanos,fingerprint)
                select task_id,?,?,?,?,cast(? as jsonb),?,?,? from task.agent_tool_budget
                where task_id=? and max_calls=?
                """)) {
            q.setString(1, profile.id()); q.setString(2, profile.version());
            q.setString(3, profile.configurationFingerprint()); q.setString(4, profile.agent().id());
            q.setString(5, profileTools(profile)); q.setInt(6, profile.maxCalls());
            q.setLong(7, profile.maxCallTime().toNanos()); q.setString(8, profile.fingerprint());
            q.setString(9, allocated.allocation().contract().id().value()); q.setInt(10, profile.maxCalls());
            if (q.executeUpdate() != 1) throw new IllegalStateException("fresh tool profile budget mismatch");
        }
    }

    /** Verifies all retained profile fields on an exact historical retry; this does not grant execution permission. */
    public static void requireProfileForAllocation(Connection connection, AllocatedResourceTask allocated,
                                                    ApprovedToolProfile profile) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(allocated); Objects.requireNonNull(profile);
        profile.requireWithin(allocated.allocation().contract(), allocated.allocation().modelProfile().agent());
        requireForAllocation(connection, allocated, profile.maxCalls());
        try (var q = connection.prepareStatement("""
                select 1 from task.agent_tool_profile where task_id=? and profile_id=? and profile_version=?
                    and configuration_fingerprint=? and agent_id=? and tools=cast(? as jsonb)
                    and max_calls=? and max_call_nanos=? and fingerprint=?
                """)) {
            q.setString(1, allocated.allocation().contract().id().value()); q.setString(2, profile.id());
            q.setString(3, profile.version()); q.setString(4, profile.configurationFingerprint());
            q.setString(5, profile.agent().id()); q.setString(6, profileTools(profile));
            q.setInt(7, profile.maxCalls()); q.setLong(8, profile.maxCallTime().toNanos()); q.setString(9, profile.fingerprint());
            try (var rows = q.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("allocation tool profile is missing or mismatched");
            }
        }
    }

    private static String profileTools(ApprovedToolProfile profile) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(profile.tools().stream().sorted().toList()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalArgumentException("invalid tool profile", impossible); }
    }

    /** Missing or differently bound rows refuse; they are never created by execution. */
    public GovernedToolGateway.ToolBudget openExisting(String thread, Subject actor, AgentTaskContract task) {
        return open(thread, actor, task, null);
    }

    private GovernedToolGateway.ToolBudget open(String thread, Subject actor, AgentTaskContract task, Integer maxCalls) {
        Objects.requireNonNull(thread); Objects.requireNonNull(actor); Objects.requireNonNull(task);
        String binding = JdbcNodeBudget.binding(thread, actor, task);
        String id = task.id().value();
        transact(c -> {
            if (maxCalls != null) try (var q = c.prepareStatement("""
                    insert into task.agent_tool_budget(task_id,max_calls)
                    select task_id,? from task.agent_node_budget
                    where task_id=? and binding=? and clock_timestamp()<deadline
                    on conflict(task_id) do nothing
                    """)) {
                q.setInt(1, maxCalls); q.setString(2, id); q.setString(3, binding); q.executeUpdate();
            }
            try (var q = c.prepareStatement("""
                    select t.max_calls from task.agent_tool_budget t join task.agent_node_budget n using (task_id)
                    where t.task_id=? and n.binding=? and clock_timestamp()<n.deadline
                    """)) {
                q.setString(1, id); q.setString(2, binding);
                try (var rows = q.executeQuery()) {
                    if (!rows.next() || (maxCalls != null && rows.getInt(1) != maxCalls))
                        throw new NodeRefused("agent tool budget is unavailable or mismatched");
                }
            }
            return null;
        });
        return new GovernedToolGateway.ToolBudget() {
            private void matching(GovernedToolGateway.Approval approved) {
                if (!approved.agent().equals(actor) || !approved.task().equals(task))
                    throw new NodeRefused("agent tool budget approval mismatch");
            }
            @Override public void admit(GovernedToolGateway.Approval approved) {
                matching(approved);
                transact(c -> {
                    try (var q = c.prepareStatement("""
                            update task.agent_tool_budget t set calls=t.calls+1 from task.agent_node_budget n
                            where t.task_id=? and n.task_id=t.task_id and n.binding=?
                              and t.calls<t.max_calls and clock_timestamp()<n.deadline returning t.calls
                            """)) {
                        q.setString(1, id); q.setString(2, binding);
                        try (var rows = q.executeQuery()) {
                            if (!rows.next()) throw new NodeRefused("agent tool budget exhausted or unavailable");
                        }
                    }
                    return null;
                });
            }
            @Override public void requireCurrent(GovernedToolGateway.Approval approved) {
                matching(approved);
                transact(c -> {
                    try (var q = c.prepareStatement("""
                            select 1 from task.agent_tool_budget t join task.agent_node_budget n using (task_id)
                            where t.task_id=? and n.binding=? and clock_timestamp()<n.deadline
                            """)) {
                        q.setString(1, id); q.setString(2, binding);
                        try (var rows = q.executeQuery()) {
                            if (!rows.next()) throw new NodeRefused("agent tool budget deadline reached or unavailable");
                        }
                    }
                    return null;
                });
            }
        };
    }
}
