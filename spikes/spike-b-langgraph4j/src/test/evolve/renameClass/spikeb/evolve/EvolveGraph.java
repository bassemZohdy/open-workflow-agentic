package spikeb.evolve;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Q4 probe. This is the version that writes a checkpoint (mode "write", interrupted before "work").
 * Each variant directory contains a different version of this class; a separate JVM resumes the checkpoint
 * with that version (mode "resume"). Prints one result line: RESULT ... or ERROR <root cause>.
 */
public class EvolveGraph {

    public static class EvolveState extends AgentState {
        public EvolveState(Map<String, Object> init) {
            super(init);
        }
    }

    public static class Evidence implements Serializable {
        private static final long serialVersionUID = 1L;
        final String text;
        final String legacy;

        Evidence(String text, String legacy) {
            this.text = text;
            this.legacy = legacy;
        }
    }

    static CompiledGraph<EvolveState> build(ObjectStreamStateSerializer<EvolveState> ser,
            PostgresSaver saver, boolean interruptBeforeWork) throws Exception {
        var g = new StateGraph<>(ser)
                .addNode("plan", node_async(s -> Map.of("topic", "evolve", "finding", new Evidence("evidence-1", "old"))))
                .addNode("work", node_async(s -> {
                    Evidence f = s.<Evidence>value("finding").orElseThrow(() -> new IllegalStateException("missing finding"));
                    return Map.of("summary", "work on " + f.text);
                }))
                .addEdge(START, "plan")
                .addEdge("plan", "work")
                .addEdge("work", END);
        return g.compile(CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptsBefore(interruptBeforeWork ? Set.of("work") : Set.of())
                .build());
    }

    public static void main(String[] args) {
        String mode = args[0];
        String schema = args[1];
        String threadId = args[2];
        try {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl("jdbc:postgresql://localhost:5432/spike_b?currentSchema=" + schema);
            ds.setUser("postgres");
            ds.setPassword("postgres");
            var ser = new ObjectStreamStateSerializer<>(EvolveState::new);
            var saver = PostgresSaver.builder().stateSerializer(ser).datasource(ds).createTables(true).build();
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            if ("write".equals(mode)) {
                var graph = build(ser, saver, true);
                graph.stream(GraphInput.args(Map.of("topic", "evolve")), config).stream().toList();
                String next = graph.stateOf(config).map(s -> s.nextNodeId()).orElse("none");
                System.out.println("WRITTEN next=" + next);
            } else {
                var graph = build(ser, saver, false);
                var outs = graph.stream(GraphInput.resume(), config).stream().toList();
                var last = outs.get(outs.size() - 1).state();
                System.out.println("RESULT summary=" + last.value("summary").orElse("<none>"));
            }
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.out.println("ERROR " + root.getClass().getName() + ": " + root.getMessage());
        }
        System.exit(0);
    }
}
