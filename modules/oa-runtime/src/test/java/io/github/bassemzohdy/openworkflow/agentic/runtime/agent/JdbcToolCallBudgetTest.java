package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JdbcToolCallBudgetTest {
    private static AgentTaskContract task(Duration time) {
        return new AgentTaskContract(UlidGenerator.shared().next(), TASK.goal(), Set.of("search"), TASK.permittedDataScopes(),
                TASK.riskTier(), new ExecutionLimits(10, time, 1000), TASK.requestingSubject(), TASK.delegationGrantId());
    }
    private static GovernedToolGateway.Approval approval(AgentTaskContract task) {
        return new GovernedToolGateway.Approval(AGENT, task, 1000);
    }

    @Test void concurrentInstancesAndRestartShareTheOriginalAllowance() throws Exception {
        var ds = AgentTestDatabase.dataSource(); var task = task(Duration.ofMinutes(5));
        String thread = "tool-budget-" + UUID.randomUUID();
        new JdbcNodeBudget(ds).registerNew(thread, AGENT, task);
        var first = new JdbcToolCallBudget(ds).registerForFreshTask(thread, AGENT, task, 2);
        var second = new JdbcToolCallBudget(ds).openExisting(thread, AGENT, task);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var start = new CountDownLatch(1);
            var results = java.util.stream.IntStream.range(0, 3).mapToObj(i -> pool.submit(() -> {
                start.await();
                try { (i % 2 == 0 ? first : second).admit(approval(task)); return true; }
                catch (NodeRefused refused) { return false; }
            })).toList();
            start.countDown(); int admitted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) admitted++;
            assertEquals(2, admitted);
        }
        assertThrows(NodeRefused.class, () -> new JdbcToolCallBudget(ds).openExisting(thread, AGENT, task).admit(approval(task)));
        assertThrows(NodeRefused.class, () -> new JdbcToolCallBudget(ds).registerForFreshTask(thread, AGENT, task, 3));
        assertThrows(NodeRefused.class, () -> new JdbcToolCallBudget(ds).openExisting(thread + "-other", AGENT, task));
        assertThrows(NodeRefused.class, () -> first.admit(approval(task(Duration.ofMinutes(5)))));
    }

    @Test void existingTasksWithoutRegistrationAreNotAutomaticallyBackfilled() {
        var ds = AgentTestDatabase.dataSource(); var task = task(Duration.ofMinutes(5));
        String thread = "unregistered-tool-" + UUID.randomUUID();
        new JdbcNodeBudget(ds).registerNew(thread, AGENT, task);
        assertThrows(NodeRefused.class, () -> new JdbcToolCallBudget(ds).openExisting(thread, AGENT, task));
    }

    @Test void retainedCounterCannotBeResetExpandedOrDeleted() throws Exception {
        var ds = AgentTestDatabase.dataSource(); var task = task(Duration.ofMinutes(5));
        String thread = "immutable-tool-" + UUID.randomUUID();
        new JdbcNodeBudget(ds).registerNew(thread, AGENT, task);
        var budget = new JdbcToolCallBudget(ds).registerForFreshTask(thread, AGENT, task, 2);
        budget.admit(approval(task));
        for (String sql : new String[] {"update task.agent_tool_budget set calls=0 where task_id=?",
                "update task.agent_tool_budget set max_calls=3 where task_id=?",
                "delete from task.agent_tool_budget where task_id=?"}) {
            try (var c = ds.getConnection(); var q = c.prepareStatement(sql)) {
                q.setString(1, task.id().value()); assertThrows(java.sql.SQLException.class, q::executeUpdate);
            }
        }
        budget.admit(approval(task));
        assertThrows(NodeRefused.class, () -> budget.admit(approval(task)));
    }

    @Test void taskDeadlineAlsoLimitsToolCallsAcrossReopen() throws Exception {
        var ds = AgentTestDatabase.dataSource(); var task = task(Duration.ofSeconds(1));
        String thread = "tool-deadline-" + UUID.randomUUID();
        new JdbcNodeBudget(ds).registerNew(thread, AGENT, task);
        var budget = new JdbcToolCallBudget(ds).registerForFreshTask(thread, AGENT, task, 2);
        budget.admit(approval(task));
        Thread.sleep(1200);
        assertThrows(NodeRefused.class, () -> budget.requireCurrent(approval(task)));
        assertThrows(NodeRefused.class, () -> budget.admit(approval(task)));
        assertThrows(NodeRefused.class, () -> new JdbcToolCallBudget(ds).openExisting(thread, AGENT, task));
    }

    @Test void gatewayRejectsAnUnprofiledDurableBudget() {
        var ds = AgentTestDatabase.dataSource(); var task = task(Duration.ofMinutes(5));
        String thread = "gateway-tool-" + UUID.randomUUID();
        new JdbcNodeBudget(ds).registerNew(thread, AGENT, task);
        var budget = new JdbcToolCallBudget(ds).registerForFreshTask(thread, AGENT, task, 1);
        var calls = new AtomicInteger();
        var registration = new GovernedToolGateway.Registration("{\"type\":\"object\"}", "{\"type\":\"object\"}",
                false, Duration.ofSeconds(2), "a".repeat(64), args -> { calls.incrementAndGet(); return "{}"; });
        var registry = Map.of("search", registration);
        var profile = new io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ApprovedToolProfile("p", "1",
                GovernedToolGateway.configurationFingerprint(registry), AGENT, Set.of("search"), 1, Duration.ofSeconds(2));
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var gateway = new GovernedToolGateway(registry, () -> Optional.of(approval(task)), profile, pep, event -> { }, budget, CLOCK);
        var call = new ToolCall(task.id(), "search", "{}");
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(call));
        assertEquals(0, calls.get());
    }
}
