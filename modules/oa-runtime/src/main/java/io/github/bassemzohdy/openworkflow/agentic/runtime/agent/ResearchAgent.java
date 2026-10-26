package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import javax.sql.DataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

/**
 * Plan -> draft -> review agent as a LangGraph4j graph, embedded in the Quarkus application.
 * Checkpoints are stored in Postgres, so a run can be resumed by thread id after a restart.
 * The workflow engine (Quarkus Flow) decides when this agent runs and what happens with its result.
 */
@ApplicationScoped
public class ResearchAgent {

    @Inject
    GovernedLlmClient llm;

    @Inject
    DataSource dataSource;

    @Inject
    AgentOutputValidator outputValidator;

    private CompiledGraph<ResearchState> graph;

    /**
     * Startup check, after the migrations of the application have run: a lazily created {@code @ApplicationScoped}
     * bean would otherwise not look at the store until its first request. A store that is not the pinned profile stops
     * the start; nothing is created or repaired here.
     */
    void onStart(@Observes StartupEvent event) {
        CheckpointProfileVerifier.verify(dataSource);
    }

    /**
     * Builds the graph on the checkpoint tables that migration V75.1 owns. The saver never creates tables: the actual
     * public profile is verified (read-only, no row decoded) before the saver exists, and again before every run.
     */
    @PostConstruct
    void build() throws Exception {
        CheckpointProfileVerifier.verify(dataSource);
        var serializer = new ObjectStreamStateSerializer<>(ResearchState::new);
        var builder = PostgresSaver.builder();
        builder.stateSerializer(serializer).datasource(dataSource).createTables(false);
        var saver = builder.build();

        var g = new StateGraph<>(serializer)
                .addNode("plan", node_async(s -> Map.of(ResearchState.PLAN,
                        outputValidator.validateText(llm.complete("Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, give 3 short numbered research steps.",
                                s.text(ResearchState.TOPIC).orElse(""))))))
                .addNode("draft", node_async(s -> Map.of(ResearchState.DRAFT,
                        outputValidator.validateText(llm.complete("Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, write a concise research brief following the plan.",
                                "Topic: " + s.text(ResearchState.TOPIC).orElse("")
                                        + "\nPlan:\n" + s.text(ResearchState.PLAN).orElse(""))))))
                .addNode("review", node_async(s -> Map.of(ResearchState.REVIEW,
                        outputValidator.validateText(llm.complete("Return JSON with exactly text (string), actions (empty array), and escalation (null). In text, reply with APPROVE or REVISE and one reason.",
                                s.text(ResearchState.DRAFT).orElse(""))))))
                .addEdge(START, "plan")
                .addEdge("plan", "draft")
                .addEdge("draft", "review")
                .addEdge("review", END);

        this.graph = g.compile(CompileConfig.builder().checkpointSaver(saver).build());
    }

    /** Runs the graph for a thread (one thread per business case) and returns the final state. */
    public ResearchState run(String threadId, String topic) {
        // before any node (and so any provider call): the store may have drifted since the graph was built
        CheckpointProfileVerifier.verify(dataSource);
        var config = RunnableConfig.builder().threadId(threadId).build();
        return graph.invoke(Map.of(ResearchState.TOPIC, topic), config)
                .orElseThrow(() -> new IllegalStateException("Agent graph produced no state"));
    }
}
