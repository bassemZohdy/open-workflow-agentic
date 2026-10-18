package spikeb;

import java.util.Map;
import java.util.Set;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;

/**
 * Separate JVM used by the restart tests. The parent test kills it with kill -9.
 *
 * Args: mode saver(v1|v2) schema threadId [draftSleepMs]
 * Modes:
 *   interrupt     start the research graph, interrupt before review, then wait to be killed
 *   slow          start the research graph, sleep inside draft, then wait to be killed
 *   resume        resume the thread with no edits
 *   edit-resume   apply a human edit to draft, then resume
 * Output lines: EXEC node, INTERRUPTED node, READY, RESULT review, ERROR class: message
 */
public final class Worker {

    private Worker() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String saverKind = args[1];
        String schema = args[2];
        String threadId = args[3];
        long sleepMs = args.length > 4 ? Long.parseLong(args[4]) : 0L;

        var pool = Db.pool(schema, 4);
        var ser = SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.saver(saverKind, ser, pool);

        boolean interrupt = mode.equals("interrupt");
        CompiledGraph<SpikeState> graph = SpikeGraphs.research(
                saver, ser, Worker::emit, sleepMs,
                interrupt ? Set.of("review") : Set.of(), Set.of());
        RunnableConfig config = Runs.config(threadId);

        Runs.Result result;
        try {
            switch (mode) {
                case "interrupt", "slow" -> result = Runs.run(graph,
                        GraphInput.args(Map.of(SpikeGraphs.TOPIC, "restart research")), config);
                case "resume" -> result = Runs.run(graph, GraphInput.resume(), config);
                case "edit-resume" -> {
                    graph.updateState(config, Map.of(SpikeGraphs.DRAFT, "HUMAN EDIT of draft"));
                    result = Runs.run(graph, GraphInput.resume(), config);
                }
                default -> throw new IllegalArgumentException("unknown mode " + mode);
            }
        } catch (Throwable t) {
            emit("ERROR " + t.getClass().getName() + ": " + t.getMessage());
            System.exit(0);
            return;
        }

        if (result.error() != null) {
            emit("ERROR " + result.rootCause());
        } else if (result.interrupted()) {
            emit("INTERRUPTED " + result.interruption().get().nodeId());
        } else {
            emit("RESULT " + result.lastState().text(SpikeGraphs.REVIEW));
        }

        if (mode.equals("interrupt") || mode.equals("slow")) {
            // Stay alive until the parent kills us, as a crashed or waiting process would.
            emit("READY");
            Thread.sleep(Long.MAX_VALUE);
        }
        System.exit(0);
    }

    private static void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
