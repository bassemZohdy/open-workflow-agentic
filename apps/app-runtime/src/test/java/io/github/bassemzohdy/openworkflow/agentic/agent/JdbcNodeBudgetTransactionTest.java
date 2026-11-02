package io.github.bassemzohdy.openworkflow.agentic.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.JdbcNodeBudget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.NodeRefused;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JdbcNodeBudgetTransactionTest {
    @Inject JdbcNodeBudget budgets;
    @Inject UserTransaction transaction;

    @Test void admissionSurvivesTheCallingTransactionRollback() throws Exception {
        var user=new Subject(SubjectKind.USER,"budget-user");
        var actor=new Subject(SubjectKind.AGENT,"budget-agent");
        var task=new AgentTaskContract(UlidGenerator.shared().next(),"test",Set.of(),Set.of(),RiskTier.LOW,
                new ExecutionLimits(1,Duration.ofMinutes(1),1000),user,UlidGenerator.shared().next());
        transaction.begin();
        try { budgets.registerNew("budget-transaction-thread",actor,task).admit(); }
        finally { transaction.rollback(); }
        var restarted=budgets.openExisting("budget-transaction-thread",actor,task);
        assertThrows(NodeRefused.class,restarted::admit);
    }
}
