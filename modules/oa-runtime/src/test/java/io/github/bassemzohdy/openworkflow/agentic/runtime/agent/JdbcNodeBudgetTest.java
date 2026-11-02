package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class JdbcNodeBudgetTest {
    private static AgentTaskContract task(int steps, Duration time) {
        return new AgentTaskContract(UlidGenerator.shared().next(), TASK.goal(), TASK.permittedTools(),
                TASK.permittedDataScopes(), TASK.riskTier(), new ExecutionLimits(steps,time,1000),
                TASK.requestingSubject(), TASK.delegationGrantId());
    }

    @Test void restartAndConcurrentWorkersShareOneTaskAllowance() throws Exception {
        var ds=AgentTestDatabase.dataSource(); var task=task(2,Duration.ofMinutes(5));
        var first=new JdbcNodeBudget(ds).registerNew("thread-a",AGENT,task);
        var second=new JdbcNodeBudget(ds).openExisting("thread-a",AGENT,task);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var start=new CountDownLatch(1);
            var results=java.util.stream.IntStream.range(0,3).mapToObj(i -> pool.submit(() -> {
                start.await(); try { (i%2==0 ? first : second).admit(); return true; }
                catch(NodeRefused expected) { return false; }
            })).toList();
            start.countDown(); int accepted=0;
            for(var result:results) if(result.get(10,TimeUnit.SECONDS)) accepted++;
            assertEquals(2,accepted);
        }
        assertThrows(NodeRefused.class, () -> new JdbcNodeBudget(ds).openExisting("thread-a",AGENT,task).admit());
        assertThrows(NodeRefused.class, () -> new JdbcNodeBudget(ds).registerNew("thread-b",AGENT,task));
        var altered=new AgentTaskContract(task.id(),task.goal(),task.permittedTools(),task.permittedDataScopes(),
                task.riskTier(),new ExecutionLimits(3,task.limits().maxWallClock(),1000),
                task.requestingSubject(),task.delegationGrantId());
        assertThrows(NodeRefused.class, () -> new JdbcNodeBudget(ds).registerNew("thread-a",AGENT,altered));
    }

    @Test void retainedBudgetCannotBeResetExtendedOrDeleted() throws Exception {
        var ds=AgentTestDatabase.dataSource(); var task=task(2,Duration.ofMinutes(1));
        new JdbcNodeBudget(ds).registerNew("immutable-thread",AGENT,task).admit();
        for(String sql : new String[]{"update task.agent_node_budget set steps=0 where task_id=?",
                "update task.agent_node_budget set deadline=deadline+interval '1 hour' where task_id=?",
                "delete from task.agent_node_budget where task_id=?"}) {
            try(var connection=ds.getConnection(); var q=connection.prepareStatement(sql)) {
                q.setString(1,task.id().value());
                assertThrows(java.sql.SQLException.class,q::executeUpdate);
            }
        }
        var reopened=new JdbcNodeBudget(ds).openExisting("immutable-thread",AGENT,task);
        reopened.admit();
        assertThrows(NodeRefused.class,reopened::admit);
    }

    @Test void deadlineSurvivesOpeningANewBudgetHandle() throws Exception {
        var ds=AgentTestDatabase.dataSource(); var task=task(2,Duration.ofSeconds(1));
        var budget=new JdbcNodeBudget(ds).registerNew("deadline-thread",AGENT,task);
        budget.admit();
        Thread.sleep(1200);
        var reopened=new JdbcNodeBudget(ds).openExisting("deadline-thread",AGENT,task);
        assertThrows(NodeRefused.class,reopened::requireCurrent);
        assertThrows(NodeRefused.class,reopened::admit);
    }
}
