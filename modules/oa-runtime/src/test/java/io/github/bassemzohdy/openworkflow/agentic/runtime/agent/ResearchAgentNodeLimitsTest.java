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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
        var task = new AgentTaskContract(TASK.id(), TASK.goal(), TASK.permittedTools(), TASK.permittedDataScopes(),
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
        agent.audit = nodeAudit;
        agent.clock = CLOCK;
        agent.build();
        return agent;
    }

    @Test void everyNodeOfAnApprovedRunIsAuditedStartedAndCompleted() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var nodeAudit = new NodeAudit();
        var agent = agent(ds, provider, contextWith(10), nodeAudit);
        String thread = "node-limits-ok-" + UUID.randomUUID();

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
        var agent = agent(ds, provider, contextWith(2), nodeAudit);
        String thread = "node-limits-stop-" + UUID.randomUUID();

        assertThrows(Exception.class, () -> agent.run(thread, "topic"));

        assertEquals(2, provider.calls.get(), "the third node (review) never reached the provider");
        assertTrue(nodeAudit.actions().contains("agent.node.refused"));
        assertNotSame(Kind.COMPLETE, new ResearchRunInspector(ds).inspect(thread).kind());
    }

    @Test void eachRunGetsItsOwnInterceptorSoARunIsNeverChargedForAnotherRunsSteps() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var agent = agent(ds, provider, contextWith(3), new NodeAudit());

        agent.run("node-limits-a-" + UUID.randomUUID(), "topic");
        agent.run("node-limits-b-" + UUID.randomUUID(), "topic"); // a shared counter would refuse this at its first node

        assertEquals(6, provider.calls.get());
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
