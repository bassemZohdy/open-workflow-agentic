package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector.Kind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The research graph with a per-run NodeInterceptor built from the approved task: limits and audit on every node. */
class ResearchAgentNodeLimitsTest {

    /** Node-level audit records, separate from the PEP's decision audit. */
    static final class NodeAudit implements AuditSink {
        final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void record(AuditEvent event) { events.add(event); }
        List<String> actions() { return events.stream().map(AuditEvent::action).toList(); }
    }

    private static ApprovedModelContext contextWith(int maxIterations) {
        var task = new AgentTaskContract(io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator.shared().next(), TASK.goal(), TASK.permittedTools(), TASK.permittedDataScopes(),
                TASK.riskTier(), new ExecutionLimits(maxIterations, Duration.ofMinutes(10), 1_000_000L),
                TASK.requestingSubject(), TASK.delegationGrantId());
        return () -> Optional.of(new ApprovedModelContext.Approved(AGENT, task, "gpt-test", 500_000L));
    }

    private static ResearchAgent agent(DataSource ds, CountingProvider provider, ApprovedModelContext context,
                                       NodeAudit nodeAudit) throws Exception {
        ResearchAgent agent = new ResearchAgent();
        agent.llm = new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ALLOW), new Audit()), context);
        agent.checkpointSchema = "public";
        agent.dataSource = ds;
        agent.outputValidator = new AgentOutputValidator(new ObjectMapper(), target -> false);
        agent.approvedContext = context;
        agent.nodeBudget = new JdbcNodeBudget(ds);
        agent.audit = nodeAudit;
        agent.clock = CLOCK;
        agent.build();
        return agent;
    }

    private static void register(ResearchAgent agent, String thread) {
        var approved=agent.approvedContext.current().orElseThrow();
        agent.nodeBudget.registerNew(thread,approved.agent(),approved.task());
    }

    @Test void everyNodeOfAnApprovedRunIsAuditedStartedAndCompleted() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var nodeAudit = new NodeAudit();
        var agent = agent(ds, provider, contextWith(10), nodeAudit);
        String thread = "node-limits-ok-" + UUID.randomUUID();

        register(agent, thread);
        agent.run(thread, "topic");

        assertSame(Kind.COMPLETE, new ResearchRunInspector(ds).inspect(thread).kind());
        assertEquals(3, provider.calls.get());
        assertEquals(List.of("agent.node.started", "agent.node.completed", "agent.node.started",
                "agent.node.completed", "agent.node.started", "agent.node.completed"), nodeAudit.actions());
    }

    @Test void theApprovedIterationLimitStopsTheRunBeforeTheNodeThatWouldExceedIt() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var nodeAudit = new NodeAudit();
        var approved = contextWith(2);
        var agent = agent(ds, provider, approved, nodeAudit);
        String thread = "node-limits-stop-" + UUID.randomUUID();

        register(agent, thread);
        assertThrows(Exception.class, () -> agent.run(thread, "topic"));

        assertEquals(2, provider.calls.get(), "the third node (review) never reached the provider");
        assertTrue(nodeAudit.actions().contains("agent.node.refused"));
        assertNotSame(Kind.COMPLETE, new ResearchRunInspector(ds).inspect(thread).kind());
        var restarted = agent(ds, provider, approved, new NodeAudit());
        assertThrows(Exception.class, () -> restarted.run(thread, "topic"));
        assertEquals(2, provider.calls.get(), "a new runtime must not reset the task allowance");
    }

    @Test void aNewThreadWithoutARegisteredBudgetIsRefusedBeforeAnyModelCall() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var candidate = agent(ds, provider, contextWith(10), new NodeAudit());
        assertThrows(NodeRefused.class, () -> candidate.run("unregistered-" + UUID.randomUUID(), "topic"));
        assertEquals(0, provider.calls.get());
    }

    @Test void anExistingCheckpointWithoutABudgetIsNotAssignedAFreshAllowance() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var approved = contextWith(10);
        String thread = "node-limits-legacy-" + UUID.randomUUID();
        var legacy = agent(ds, provider, approved, new NodeAudit());
        legacy.approvedContext = NONE; // Model context remains approved; model a checkpoint from before budget support.
        legacy.run(thread, "topic");
        assertEquals(3, provider.calls.get());
        var upgraded = agent(ds, provider, approved, new NodeAudit());
        assertThrows(NodeRefused.class, () -> upgraded.run(thread, "topic"));
        assertEquals(3, provider.calls.get(), "legacy checkpoint must not receive a new budget");
    }

    @Test void eachRunGetsItsOwnInterceptorSoARunIsNeverChargedForAnotherRunsSteps() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var agent = agent(ds, provider, contextWith(3), new NodeAudit());

        String first = "node-limits-a-" + UUID.randomUUID();
        register(agent, first);
        agent.run(first, "topic");
        var other = agent(ds, provider, contextWith(3), new NodeAudit());
        String second = "node-limits-b-" + UUID.randomUUID();
        register(other, second);
        other.run(second, "topic");

        assertEquals(6, provider.calls.get());
    }

    @Test void aLateFinalModelResponseCannotCompleteTheGraph() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var time = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var provider = new CountingProvider(n -> {
            if (n == 3) time.set(NOW.plus(Duration.ofMinutes(10)));
            return OK;
        });
        var nodeAudit = new NodeAudit();
        var agent = agent(ds, provider, contextWith(10), nodeAudit);
        agent.clock = clock;
        String thread = "node-limits-late-final-" + UUID.randomUUID();

        register(agent, thread);
        assertThrows(Exception.class, () -> agent.run(thread, "topic"));

        assertEquals(3, provider.calls.get(), "the final model call completed, but its late result is withheld");
        assertNotSame(Kind.COMPLETE, new ResearchRunInspector(ds).inspect(thread).kind());
        assertEquals("agent.node.refused", nodeAudit.actions().getLast());
    }

    @Test void withoutAnApprovedContextTheGraphStaysUnwrappedAndTheModelCallStillRefuses() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var nodeAudit = new NodeAudit();
        var agent = agent(ds, provider, NONE, nodeAudit);

        assertThrows(Exception.class, () -> agent.run("node-limits-none-" + UUID.randomUUID(), "topic"));

        assertEquals(0, provider.calls.get());
        assertTrue(nodeAudit.events.isEmpty(), "no interceptor without an approved task to take limits from");
    }
}
