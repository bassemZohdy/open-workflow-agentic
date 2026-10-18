package spikeb;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptionMetadata;

/** Runs a graph and records what it emitted, how it ended, and any error. */
public final class Runs {

    private Runs() {}

    /** Outcome of one run. */
    public record Result(List<String> nodes, SpikeState lastState, Object doneValue, Throwable error) {

        public Optional<InterruptionMetadata<SpikeState>> interruption() {
            Object v = doneValue;
            if (v instanceof org.bsc.langgraph4j.GraphResult gr
                    && gr.type() == org.bsc.langgraph4j.GraphResult.Type.INTERRUPTION_METADATA) {
                v = gr.result();
            }
            if (v instanceof InterruptionMetadata<?> m) {
                @SuppressWarnings("unchecked")
                InterruptionMetadata<SpikeState> typed = (InterruptionMetadata<SpikeState>) m;
                return Optional.of(typed);
            }
            return Optional.empty();
        }

        public boolean interrupted() {
            return interruption().isPresent();
        }

        public String rootCause() {
            if (error == null) {
                return "";
            }
            Throwable t = error;
            while (t.getCause() != null && t.getCause() != t) {
                t = t.getCause();
            }
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    public static Result run(CompiledGraph<SpikeState> graph, GraphInput input, RunnableConfig config) {
        List<String> nodes = new ArrayList<>();
        SpikeState last = null;
        AsyncGenerator.Cancellable<NodeOutput<SpikeState>> gen = graph.stream(input, config);
        try {
            for (NodeOutput<SpikeState> out : (Iterable<NodeOutput<SpikeState>>) gen.stream()::iterator) {
                // The stream also emits a __START__ marker before the first real node. It is not a node.
                if (!out.node().equals(org.bsc.langgraph4j.GraphDefinition.START) && !out.node().equals(org.bsc.langgraph4j.GraphDefinition.END)) {
                    nodes.add(out.node());
                }
                last = out.state();
            }
            return new Result(nodes, last, AsyncGenerator.resultValue(gen).orElse(null), null);
        } catch (Throwable t) {
            return new Result(nodes, last, null, t);
        }
    }

    public static RunnableConfig config(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }
}
