package spikeb;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.junit.jupiter.api.Test;

/**
 * Q3: checkpoint rows, bytes and read amplification per run, for 10, 50 and 200 steps.
 * The state holds a topic, a 500-character plan and a draft that grows by about 380 characters per step.
 * Every run uses a fresh schema so the row counts belong to that run only.
 */
class Q03CheckpointGrowthTest {

    private static final String TOPIC = "Consent-based autonomous services for agency case handling in the super app platform";
    private static final String PLAN = "Plan: ".repeat(100);
    private static final String[] WORDS = {
        "agency", "consent", "audit", "retention", "service", "integration", "citizen", "license", "payment",
        "gateway", "policy", "identity", "delegation", "workflow", "agent", "checkpoint", "resume", "tenant",
        "evidence", "review", "approval", "escalation", "dashboard", "permit", "record", "notice", "sovereignty",
        "encryption", "queue", "retry", "timeout", "ledger", "contract", "schedule", "portal", "municipality",
        "vehicle", "address", "certificate", "signature", "registry", "statement", "budget", "outage", "fallback",
        "call", "center", "channel", "validation", "schema", "version", "migration", "snapshot", "interrupt"
    };

    /** Deterministic varied prose, so Postgres compression behaves as it would on real text. */
    static String prose(int seed, int chars) {
        var random = new java.util.Random(seed);
        var sb = new StringBuilder(chars + 16);
        while (sb.length() < chars) {
            sb.append(WORDS[random.nextInt(WORDS.length)]).append(random.nextInt(10) == 0 ? ". " : " ");
        }
        return sb.substring(0, chars);
    }

    record Row(String serializer, int steps, long rows, long textChars, long tableBytes, long totalRelationBytes,
            long rowsRead, long charsRead, long statements, long connectionsOpened) {

        @Override
        public String toString() {
            return String.format(
                    "%-5s steps=%-4d rows=%-5d stateChars=%-10d stateBytes=%-10d tableTotalBytes=%-10d "
                            + "rowsRead=%-7d charsRead=%-11d statements=%-5d connections=%d",
                    serializer, steps, rows, textChars, tableBytes, totalRelationBytes, rowsRead, charsRead,
                    statements, connectionsOpened);
        }
    }

    static Row measure(String serializerName, int steps) throws Exception {
        String schema = "q03_" + serializerName + "_" + steps;
        Db.resetSchema(schema);
        var pool = Db.pool(schema, 4);
        var stats = new Db.DbStats();
        DataSource counting = Db.counting(pool, stats);
        StateSerializer<SpikeState> ser = "json".equals(serializerName)
                ? new JsonSpikeSerializer()
                : SpikeGraphs.javaSerializer();
        var saver = SpikeGraphs.v1(ser, counting);
        var graph = SpikeGraphs.stepper(saver, ser, steps, i -> prose(i + 1, 380));
        String thread = "q03-" + serializerName + "-" + steps;

        stats.reset();
        Runs.Result r = Runs.run(graph,
                GraphInput.args(Map.of(SpikeGraphs.TOPIC, TOPIC, SpikeGraphs.PLAN, PLAN, SpikeGraphs.DRAFT, "")),
                Runs.config(thread));
        assertNull(r.error(), r.rootCause());

        long rows = Db.queryLong("select count(*) from " + schema + ".lg4jcheckpoint");
        long chars = Db.queryLong("select coalesce(sum(length(state_data)),0) from " + schema + ".lg4jcheckpoint");
        long bytes = Db.queryLong("select coalesce(sum(pg_column_size(state_data)),0) from " + schema + ".lg4jcheckpoint");
        long total = Db.queryLong("select pg_total_relation_size('" + schema + ".lg4jcheckpoint')");
        return new Row(serializerName, steps, rows, chars, bytes, total,
                stats.rowsRead.get(), stats.charsRead.get(), stats.statements.get(), stats.connectionsOpened.get());
    }

    @Test
    void Q03_checkpointGrowthAndReadAmplification_javaSerializer() throws Exception {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {10, 50, 200}) {
            rows.add(measure("java", n));
        }
        rows.forEach(r -> System.out.println("Q03 " + r));

        Row r10 = rows.get(0), r50 = rows.get(1), r200 = rows.get(2);
        assertTrue(r10.rows() >= 11, "one checkpoint per step plus START: " + r10);
        assertTrue(r200.rows() >= 201, "one checkpoint per step plus START: " + r200);
        // Stored text grows with the draft, and every checkpoint holds the whole state: quadratic in steps.
        assertTrue(r200.textChars() > 8L * r50.textChars(),
                "stored chars grow superlinearly (linear would be 4x): 200 vs 50 = "
                        + (double) r200.textChars() / r50.textChars());
        // The saver reloads all checkpoints of the thread on each put and get: reads grow ~quadratically.
        assertTrue(r200.rowsRead() > 8L * r50.rowsRead(),
                "rows read grow ~quadratically: 200 vs 50 = " + (double) r200.rowsRead() / r50.rowsRead());
    }

    @Test
    void Q03_checkpointGrowth_jsonSerializerComparison() throws Exception {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {10, 50, 200}) {
            rows.add(measure("json", n));
        }
        rows.forEach(r -> System.out.println("Q03 " + r));
        assertTrue(rows.get(2).textChars() > 8L * rows.get(1).textChars());
    }
}
