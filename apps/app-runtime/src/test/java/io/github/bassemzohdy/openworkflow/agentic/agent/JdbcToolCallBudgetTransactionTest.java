package io.github.bassemzohdy.openworkflow.agentic.agent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedToolGateway;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.JdbcNodeBudget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.JdbcToolCallBudget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.NodeRefused;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JdbcToolCallBudgetTransactionTest {
    @Inject JdbcNodeBudget nodes;
    @Inject DataSource data;
    @Inject TransactionManager manager;
    @Inject UserTransaction transaction;

    @Test void admissionSurvivesTheCallingTransactionRollback() throws Exception {
        var user = new Subject(SubjectKind.USER, "tool-budget-user");
        var actor = new Subject(SubjectKind.AGENT, "tool-budget-agent");
        var task = new AgentTaskContract(UlidGenerator.shared().next(), "test", Set.of("search"), Set.of(), RiskTier.LOW,
                new ExecutionLimits(1, Duration.ofMinutes(1), 1000), user, UlidGenerator.shared().next());
        String thread = "tool-budget-transaction-" + UUID.randomUUID();
        nodes.registerNew(thread, actor, task);
        var budgets = new JdbcToolCallBudget(data, manager);
        var approved = new GovernedToolGateway.Approval(actor, task, 1000);
        transaction.begin();
        try { budgets.registerForFreshTask(thread, actor, task, 1).admit(approved); }
        finally { transaction.rollback(); }
        var restarted = budgets.openExisting(thread, actor, task);
        assertThrows(NodeRefused.class, () -> restarted.admit(approved));
    }
}
