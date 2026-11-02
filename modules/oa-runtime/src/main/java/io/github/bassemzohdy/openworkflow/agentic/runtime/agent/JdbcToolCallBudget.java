package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.transaction.TransactionManager;
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
        if (maxCalls < 1 || maxCalls > 100_000) throw new IllegalArgumentException("invalid tool-call ceiling");
        return open(thread, actor, task, maxCalls);
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
