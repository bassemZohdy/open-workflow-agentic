package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.CheckpointStateIncompatibleException.Reason;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.junit.jupiter.api.Test;

/** The versioned research checkpoint state: written on every new run, checked before a stored run continues. */
class ResearchAgentStateVersionTest {

    private static ResearchAgent agent(DataSource ds, CountingProvider provider) throws Exception {
        ResearchAgent agent = new ResearchAgent();
        agent.llm = new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ALLOW), new Audit()), APPROVED);
        agent.checkpointSchema = "public";
        agent.dataSource = ds;
        agent.outputValidator = new AgentOutputValidator(new ObjectMapper(), target -> false);
        agent.build();
        return agent;
    }

    private static void store(DataSource ds, String thread, Map<String, Object> state) throws Exception {
        var saver = PostgresSaver.builder().datasource(ds).createTables(false)
                .stateSerializer(new ObjectStreamStateSerializer<>(ResearchState::new)).build();
        saver.put(RunnableConfig.builder().threadId(thread).build(),
                Checkpoint.builder().state(state).nodeId("plan").nextNodeId("draft").build());
    }

    @Test void aNewRunRecordsTheCurrentStateVersion() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var candidate = agent(ds, new CountingProvider());
        var task = new io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract(
                io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator.shared().next(), TASK.goal(),
                TASK.permittedTools(), TASK.permittedDataScopes(), TASK.riskTier(), TASK.limits(),
                TASK.requestingSubject(), TASK.delegationGrantId());
        ApprovedModelContext context = () -> java.util.Optional.of(new ApprovedModelContext.Approved(AGENT, task, "gpt-test", 500_000L));
        candidate.approvedContext = context;
        candidate.llm = new GovernedLlmClient(new CountingProvider(), pep(new Engine(DecisionOutcome.ALLOW), new Audit()), context);
        candidate.audit = new Audit();
        candidate.nodeBudget = new JdbcNodeBudget(ds);
        String thread = "state-version-new-" + UUID.randomUUID();
        candidate.nodeBudget.registerNew(thread, AGENT, task);
        var state = candidate.run(thread, "topic");
        assertEquals(ResearchStateVersion.CURRENT, ((Number) state.value(ResearchStateVersion.KEY).orElseThrow()).intValue());
    }

    @Test void aStoredStateFromANewerBuildIsRefusedBeforeAnyProviderCall() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        String thread = "state-version-newer-" + UUID.randomUUID();
        store(ds, thread, Map.of(ResearchState.TOPIC, "owned", ResearchStateVersion.KEY, ResearchStateVersion.CURRENT + 1));

        var refused = assertThrows(CheckpointStateIncompatibleException.class, () -> agent(ds, provider).run(thread, "topic"));

        assertEquals(Reason.NEWER, refused.reason());
        assertEquals(0, provider.calls.get());
    }

    @Test void aStoredStateWithAnUnreadableMarkerIsRefusedBeforeAnyProviderCall() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        String thread = "state-version-bad-" + UUID.randomUUID();
        store(ds, thread, Map.of(ResearchState.TOPIC, "owned", ResearchStateVersion.KEY, "one"));

        var refused = assertThrows(CheckpointStateIncompatibleException.class, () -> agent(ds, provider).run(thread, "topic"));

        assertEquals(Reason.MALFORMED, refused.reason());
        assertEquals(0, provider.calls.get());
    }
}
