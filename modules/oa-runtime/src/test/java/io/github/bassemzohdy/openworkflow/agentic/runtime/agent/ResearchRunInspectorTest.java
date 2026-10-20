package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector.Inspection;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector.Kind;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** The inspector against the real checkpoint tables, written by the real graph with a scripted model. */
class ResearchRunInspectorTest {

    private static final String OK = "{\"text\":\"1. a 2. b 3. c\",\"actions\":[],\"escalation\":null}";

    private static DataSource db() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        return ds;
    }

    private static ResearchAgent agent(DataSource ds, LlmClient llm) throws Exception {
        ResearchAgent agent = new ResearchAgent();
        agent.llm = llm;
        agent.dataSource = ds;
        agent.outputValidator = new AgentOutputValidator(new ObjectMapper(), target -> false);
        agent.build();
        return agent;
    }

    /** A model that answers the first {@code ok} calls and then throws. */
    private static LlmClient failingAfter(int ok, AtomicInteger calls) {
        return (system, user) -> {
            if (calls.getAndIncrement() >= ok) {
                throw new IllegalStateException("model down");
            }
            return OK;
        };
    }

    @Test
    void aThreadThatNeverRanIsNone() throws Exception {
        DataSource ds = db();
        agent(ds, (s, u) -> OK); // creates the tables
        Inspection result = new ResearchRunInspector(ds).inspect("never-" + UUID.randomUUID());
        assertEquals(Kind.NONE, result.kind());
        assertEquals(0, result.runs());
    }

    @Test
    void aCompletedRunIsCompleteWithAStableDigest() throws Exception {
        DataSource ds = db();
        String thread = "done-" + UUID.randomUUID();
        agent(ds, (s, u) -> OK).run(thread, "topic");
        ResearchRunInspector inspector = new ResearchRunInspector(ds);
        Inspection first = inspector.inspect(thread);
        assertEquals(Kind.COMPLETE, first.kind());
        assertEquals(1, first.runs());
        assertEquals("review", first.lastNode());
        assertNotNull(first.outputDigest());
        assertEquals(first, inspector.inspect(thread));
    }

    @Test
    void aRunThatFailedPartwayIsPartialAndNotComplete() throws Exception {
        DataSource ds = db();
        String thread = "failed-" + UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> agent(ds, failingAfter(1, calls)).run(thread, "topic"));
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        // measured: the graph leaves the checkpoint before the failed node, and the thread stays open
        assertEquals(Kind.PARTIAL, result.kind());
        assertEquals("plan", result.lastNode());
        assertEquals("draft", result.nextNode());
        assertEquals(2, result.checkpointCount());
        assertNull(result.outputDigest());
    }

    @Test
    void aRunThatFailedInTheFirstNodeIsPartialAtStart() throws Exception {
        DataSource ds = db();
        String thread = "failed-first-" + UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> agent(ds, failingAfter(0, new AtomicInteger())).run(thread, "topic"));
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.PARTIAL, result.kind());
        assertEquals("__START__", result.lastNode());
        assertEquals("plan", result.nextNode());
    }

    @Test
    void anEntryRepeatedAfterCompletionIsRepeated() throws Exception {
        DataSource ds = db();
        String thread = "twice-" + UUID.randomUUID();
        ResearchAgent agent = agent(ds, (s, u) -> OK);
        agent.run(thread, "topic");
        agent.run(thread, "topic");
        Inspection result = new ResearchRunInspector(ds).inspect(thread);
        assertEquals(Kind.REPEATED, result.kind());
        assertEquals(2, result.runs());
        assertNull(result.outputDigest());
    }

    @Test
    void anUnreadableStoreIsAFailureNotNone() {
        PGSimpleDataSource broken = new PGSimpleDataSource();
        broken.setUrl("jdbc:postgresql://localhost:1/none");
        assertThrows(IllegalStateException.class, () -> new ResearchRunInspector(broken).inspect("x"));
    }
}
