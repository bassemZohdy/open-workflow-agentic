package io.github.bassemzohdy.openworkflow.spikec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.testkit.model.ScriptedLlmClient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * In-process regression tests for {@link ResearchGraph#run} (issue #9, requirement 2). No HTTP and no child
 * process: the graph runs against the database named by SPIKEC_JDBC_URL, and each failure is injected through a
 * test seam. Every test asserts the side-effect counters: a failure on resume must not restart the graph, so the
 * draft node (the model call and its step) and the publish node (the effect) must not run again.
 */
class ResearchGraphResumeIT {

    /**
     * Makes the checkpoint store fail on the next {@code get} calls, as a transient database error would. The
     * failure is transient on purpose: a fallback to a fresh start would then succeed and replay the draft.
     */
    static final class FlakySaver {

        volatile int failGets;

        BaseCheckpointSaver wrap(BaseCheckpointSaver real) {
            return (BaseCheckpointSaver) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {BaseCheckpointSaver.class}, (proxy, method, args) -> {
                        if (method.getName().equals("get") && failGets > 0) {
                            failGets--;
                            throw new RuntimeException("checkpoint store unavailable (test)");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    /** A mapper whose next reads fail, as a broken serializer would on a checkpoint read. Transient, as above. */
    static final class BreakableMapper extends ObjectMapper {

        volatile int failReads;

        @Override
        public <T> T readValue(String content, TypeReference<T> valueTypeRef)
                throws JsonProcessingException, JsonMappingException {
            if (failReads > 0) {
                failReads--;
                throw new JsonMappingException(null, "serializer failure (test)");
            }
            return super.readValue(content, valueTypeRef);
        }
    }

    private PGSimpleDataSource dataSource;
    private Store store;
    private ResearchGraph graph;
    private FlakySaver saver;
    private BreakableMapper mapper;
    private Ctx ctx;

    @BeforeEach
    void setUp() throws Exception {
        Harness.resetDatabase();
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(Harness.JDBC);
        dataSource.setUser("postgres");
        dataSource.setPassword("postgres");
        store = new Store();
        store.dataSource = dataSource;
        store.init();
        ctx = new Ctx("t1", "u1", UUID.randomUUID().toString(), "c-resume", "2026-10-20T00:00:00Z", 5, "resume");
        saver = new FlakySaver();
        mapper = new BreakableMapper();
        graph = newGraph(saver, mapper);
    }

    private ResearchGraph newGraph(FlakySaver flakySaver, BreakableMapper breakable) throws Exception {
        ResearchGraph g = new ResearchGraph();
        ScriptedLlmClient llm = ScriptedLlmClient.create();
        for (int i = 0; i < 5; i++) {
            llm.answer("brief " + i);
        }
        g.llm = llm;
        g.store = store;
        g.dataSource = dataSource;
        g.saverDecorator = flakySaver::wrap;
        g.serializer = new JsonSpikeSerializer(breakable);
        g.build();
        return g;
    }

    private long steps(String node) {
        return store.steps(ctx.threadId().value()).stream().filter(s -> node.equals(s.get("node"))).count();
    }

    private long effects() {
        return store.effectCount(ctx.threadId().value());
    }

    /** Runs to the approval interrupt: the draft ran once, publish has not run. */
    private void runToApproval() {
        assertEquals(ResearchGraph.STATUS_AWAITING, graph.run(ctx, "A-http", null).status());
        assertEquals(1, steps("draft"));
        assertEquals(0, steps("publish"));
        assertEquals(0, effects());
    }

    private static boolean chainContains(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** Control: a resume with nothing failing completes once, and the draft is not run again. */
    @Test
    void ResumeWithoutFaultCompletesWithoutReplay() throws Exception {
        runToApproval();
        assertEquals(ResearchGraph.STATUS_COMPLETED, graph.run(ctx, "A-http", null).status());
        assertEquals(1, steps("draft"));
        assertEquals(1, steps("publish"));
        assertEquals(1, effects());
    }

    /**
     * Control: the checkpoint is positively missing (the run record exists, the saver has no checkpoint for the
     * thread). The fresh start is the intended fallback and is allowed.
     */
    @Test
    void PositivelyMissingCheckpointStartsFresh() throws Exception {
        store.noteReceived("A-http", ctx, null, "RUNNING");
        assertEquals(ResearchGraph.STATUS_AWAITING, graph.run(ctx, "A-http", null).status());
        assertEquals(1, steps("draft"));
        assertEquals(0, steps("publish"));
    }

    /** Requirement 2: a checkpoint-store failure on resume is propagated, not replaced by a fresh run. */
    @Test
    void CheckpointStoreFailureOnResumeIsPropagatedWithoutReplay() throws Exception {
        runToApproval();
        saver.failGets = 1;
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> graph.run(ctx, "A-http", null));
        assertTrue(chainContains(thrown, "checkpoint store unavailable"), "surfaced: " + thrown);
        assertEquals(1, steps("draft"), "the draft was run again");
        assertEquals(0, steps("publish"));
        assertEquals(0, effects());
    }

    /** Requirement 2: a serializer failure while reading the checkpoint is propagated, not replaced by a fresh run. */
    @Test
    void SerializerFailureOnResumeIsPropagatedWithoutReplay() throws Exception {
        runToApproval();
        mapper.failReads = 1;
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> graph.run(ctx, "A-http", null));
        assertTrue(chainContains(thrown, "serializer failure"), "surfaced: " + thrown);
        assertEquals(1, steps("draft"), "the draft was run again");
        assertEquals(0, steps("publish"));
        assertEquals(0, effects());
    }

    /** Requirement 2: a node that throws on resume is propagated, and the nodes before it are not run again. */
    @Test
    void NodeFailureOnResumeIsPropagatedWithoutReplay() throws Exception {
        runToApproval();
        graph.nodeFault = node -> {
            if ("publish".equals(node)) {
                throw new IllegalStateException("publish failed (test)");
            }
        };
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> graph.run(ctx, "A-http", null));
        assertTrue(chainContains(thrown, "publish failed"), "surfaced: " + thrown);
        assertEquals(1, steps("draft"), "the draft was run again");
        assertEquals(0, steps("publish"));
        assertEquals(0, effects());
    }
}
