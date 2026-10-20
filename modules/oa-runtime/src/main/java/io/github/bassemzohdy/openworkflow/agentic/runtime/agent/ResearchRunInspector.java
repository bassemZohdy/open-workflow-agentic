package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Read-only view of what the research graph has left in its checkpoint store for one thread (WP-103, proposal in
 * docs/handoffs/claude/WP-103-execution-adapter.md). It never invokes the graph and never calls a model, so a recovery
 * read can say what happened without causing new work.
 *
 * <p>It reads {@code lg4jthread} and {@code lg4jcheckpoint} directly, because the saver's own lookup only sees an
 * unreleased thread and a completed run is released. Several thread rows for one name mean the graph was entered more
 * than once, which an invocation must never do; that is reported as {@link Kind#REPEATED}, never resolved by picking
 * one. The stored state is Java-serialized bytes: it is digested as stored and never deserialized here.
 *
 * <p>This is data, not authority: the caller must still hold a current execution permission, and the result says
 * nothing about who may see the output.
 */
@ApplicationScoped
public class ResearchRunInspector {

    /** What the checkpoint store shows for one thread name. */
    public enum Kind {
        /** No thread row exists: the graph never produced a checkpoint here. */
        NONE,
        /** One thread whose last checkpoint is not the end of the graph: in progress, failed or killed. Unknown. */
        PARTIAL,
        /** One thread whose last checkpoint leads to the end of the graph. */
        COMPLETE,
        /** More than one thread row for the name: a repeated entry. Never chosen between. */
        REPEATED
    }

    /**
     * The result. {@code runs} is the number of thread rows. For PARTIAL and COMPLETE, {@code checkpointId}, {@code lastNode}
     * and {@code nextNode} describe the last checkpoint and {@code checkpointCount} how many there are; for COMPLETE,
     * {@code outputDigest} binds that exact checkpoint (id and stored bytes) under the domain
     * {@code oa.agent.research.output.v1}.
     */
    public record Inspection(Kind kind, int runs, String checkpointId, String lastNode, String nextNode,
                             int checkpointCount, String outputDigest) {
    }

    /** The graph's end marker in {@code next_node_id}. */
    static final String END = "__END__";
    static final String DOMAIN = "oa.agent.research.output.v1";

    private final DataSource dataSource;

    @Inject
    public ResearchRunInspector(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Inspects the thread by its exact name.
     *
     * @throws IllegalStateException the store could not be read; a read failure is never "nothing happened"
     */
    public Inspection inspect(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (!tablesExist(connection)) {
                return new Inspection(Kind.NONE, 0, null, null, null, 0, null);
            }
            int runs = count(connection, "select count(*) from lg4jthread where thread_name = ?", threadName);
            if (runs == 0) {
                return new Inspection(Kind.NONE, 0, null, null, null, 0, null);
            }
            if (runs > 1) {
                return new Inspection(Kind.REPEATED, runs, null, null, null, 0, null);
            }
            try (PreparedStatement last = connection.prepareStatement(
                    "select c.checkpoint_id, c.node_id, c.next_node_id, c.state_data,"
                            + " (select count(*) from lg4jcheckpoint x where x.thread_id = t.thread_id)"
                            + " from lg4jthread t join lg4jcheckpoint c on c.thread_id = t.thread_id"
                            + " where t.thread_name = ? order by c.saved_at desc, c.checkpoint_id desc limit 1")) {
                last.setString(1, threadName);
                try (ResultSet row = last.executeQuery()) {
                    if (!row.next()) {
                        // a thread row with no checkpoint: the graph was entered and has left nothing
                        return new Inspection(Kind.PARTIAL, 1, null, null, null, 0, null);
                    }
                    String id = row.getString(1);
                    String node = row.getString(2);
                    String next = row.getString(3);
                    int checkpoints = row.getInt(5);
                    boolean complete = END.equals(next);
                    String digest = complete ? CanonicalEncoder.sha256Hex(new CanonicalEncoder(DOMAIN)
                            .text(id).text(row.getString(4)).toBytes()) : null;
                    return new Inspection(complete ? Kind.COMPLETE : Kind.PARTIAL, 1, id, node, next, checkpoints,
                            digest);
                }
            }
        } catch (SQLException unreadable) {
            throw new IllegalStateException("the checkpoint store could not be read", unreadable);
        }
    }

    private static boolean tablesExist(Connection connection) throws SQLException {
        return count(connection, "select count(*) from information_schema.tables where table_name in"
                + " ('lg4jthread', 'lg4jcheckpoint') and table_schema = current_schema()") == 2;
    }

    private static int count(Connection connection, String sql, String... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
