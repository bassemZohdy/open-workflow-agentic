package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionManager;
import java.util.Objects;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import javax.sql.DataSource;

/** Independently committed, per-approved-task graph node admissions and database-clock deadline. */
@ApplicationScoped
@Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcNodeBudget {
    private final DataSource dataSource;
    private final TransactionManager transactions;
    @Inject public JdbcNodeBudget(DataSource dataSource, TransactionManager transactions) {
        this.dataSource = Objects.requireNonNull(dataSource); this.transactions = Objects.requireNonNull(transactions);
    }
    /** For a non-enlisted datasource in standalone tests. */
    public JdbcNodeBudget(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource); this.transactions = null; }

    private <T> T transact(Transactions.Work<T> work) {
        java.util.function.Supplier<T> execute = () -> Transactions.inBoundedPostgresTransaction(dataSource,work,2000,5000,5000);
        return transactions == null ? execute.get() : Transactions.inSuspendedTransaction(transactions,execute);
    }

    /**
     * Internal preparation only. A caller must prove this task is genuinely new; request fields, an absent budget or
     * an absent checkpoint do not establish that fact. No production task-start producer is wired yet.
     */
    public NodeInterceptor.DurableBudget registerNew(String thread, Subject actor, AgentTaskContract task) {
        return open(thread, actor, task, true);
    }

    /**
     * Insert only while creating the matching resource allocation in the SAME owned transaction. This is not an
     * authority check or a way to backfill historical allocations. A conflict fails the entire allocation.
     */
    public static void insertForFreshAllocation(Connection connection, AllocatedResourceTask allocated) throws SQLException {
        Objects.requireNonNull(connection); Objects.requireNonNull(allocated);
        if (connection.getAutoCommit()) throw new IllegalArgumentException("fresh allocation requires an owned transaction");
        var allocation = allocated.allocation();
        var task = allocation.contract();
        var thread = allocation.scope().threadId().value();
        var actor = allocation.modelProfile().agent();
        if (!allocated.deadline().isAfter(allocated.createdAt())
                || allocated.deadline().isAfter(allocated.createdAt().plus(task.limits().maxWallClock())))
            throw new IllegalArgumentException("allocation deadline exceeds approved task");
        try (var q = connection.prepareStatement("""
                insert into task.agent_node_budget(task_id,binding,max_steps,started_at,deadline)
                values (?,?,?,?,?)
                """)) {
            q.setString(1,task.id().value()); q.setString(2,binding(thread,actor,task));
            q.setInt(3,task.limits().maxIterations()); q.setTimestamp(4,Timestamp.from(allocated.createdAt()));
            q.setTimestamp(5,Timestamp.from(allocated.deadline())); q.executeUpdate();
        }
    }

    /** Refuses an unregistered task, including when no checkpoint exists. */
    public NodeInterceptor.DurableBudget openExisting(String thread, Subject actor, AgentTaskContract task) {
        return open(thread, actor, task, false);
    }

    private NodeInterceptor.DurableBudget open(String thread, Subject actor, AgentTaskContract task, boolean mayCreate) {
        Objects.requireNonNull(thread); Objects.requireNonNull(actor); Objects.requireNonNull(task);
        String id = task.id().value();
        String binding = binding(thread, actor, task);
        transact(c -> {
            if (mayCreate) try (var q = c.prepareStatement("""
                    insert into task.agent_node_budget(task_id,binding,max_steps,started_at,deadline)
                    select ?,?,?,t,t+(? * interval '1 microsecond') from (select clock_timestamp() as t) s
                    on conflict (task_id) do nothing
                    """)) {
                q.setString(1,id); q.setString(2,binding); q.setInt(3,task.limits().maxIterations());
                q.setLong(4,task.limits().maxWallClock().toNanos()/1000); q.executeUpdate();
            }
            try (var q = c.prepareStatement("select binding from task.agent_node_budget where task_id=? for update")) {
                q.setString(1,id);
                try (var rows = q.executeQuery()) {
                    if (!rows.next() || !binding.equals(rows.getString(1)))
                        throw new NodeRefused("agent task budget binding mismatch");
                }
            }
            return null;
        });
        return new NodeInterceptor.DurableBudget() {
            @Override public void admit() {
                transact(c -> {
                    try (var q = c.prepareStatement("""
                            update task.agent_node_budget set steps=steps+1
                            where task_id=? and binding=? and steps<max_steps and clock_timestamp()<deadline
                            returning steps
                            """)) {
                        q.setString(1,id); q.setString(2,binding);
                        try (var rows=q.executeQuery()) {
                            if (!rows.next()) throw new NodeRefused("durable agent node budget exhausted or unavailable");
                        }
                    }
                    return null;
                });
            }
            @Override public void requireCurrent() {
                transact(c -> {
                    try (var q=c.prepareStatement("select 1 from task.agent_node_budget where task_id=? and binding=? and clock_timestamp()<deadline")) {
                        q.setString(1,id); q.setString(2,binding);
                        try (var rows=q.executeQuery()) {
                            if(!rows.next()) throw new NodeRefused("durable agent deadline reached or unavailable");
                        }
                    }
                    return null;
                });
            }
        };
    }

    static String binding(String thread, Subject actor, AgentTaskContract task) {
        var e = new CanonicalEncoder("oa.agent.node-budget.v1").text(thread).text(actor.kind().name()).text(actor.id())
                .text(task.id().value()).text(task.goal()).text(task.riskTier().name())
                .number(task.limits().maxIterations()).text(task.limits().maxWallClock().toString())
                .number(task.limits().maxCostMicros()).text(task.requestingSubject().kind().name())
                .text(task.requestingSubject().id()).text(task.delegationGrantId().value());
        e.number(task.permittedTools().size()); task.permittedTools().stream().sorted().forEach(e::text);
        e.number(task.permittedDataScopes().size()); task.permittedDataScopes().stream().sorted().forEach(e::text);
        return CanonicalEncoder.sha256Hex(e.toBytes());
    }
}
