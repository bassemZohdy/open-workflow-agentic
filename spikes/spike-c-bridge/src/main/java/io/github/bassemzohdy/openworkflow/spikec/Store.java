package io.github.bassemzohdy.openworkflow.spikec;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Own tables in schema spikec (the registry of workflow instances, the agent run record, the step log and the
 * simulated external system). Plain JDBC on purpose: the spike has one small schema and no ORM choices to make.
 */
@ApplicationScoped
public class Store {

    static final String SCHEMA_SQL = """
            create schema if not exists spikec;
            create table if not exists spikec.bridge_instance (
              instance_id text primary key,
              bridge text not null,
              tenant text not null,
              user_id text not null,
              correlation_id text not null,
              deadline text not null,
              budget int not null,
              topic text not null,
              thread_id text not null unique,
              engine_id text,
              status text not null,
              updated_at timestamptz not null default now());
            create table if not exists spikec.agent_run (
              thread_id text primary key,
              instance_id text not null unique,
              bridge text not null,
              status text not null,
              received_ctx text not null,
              received_header_correlation text,
              updated_at timestamptz not null default now());
            create table if not exists spikec.agent_step (
              id bigserial primary key,
              thread_id text not null,
              node text not null,
              correlation_in_state text not null,
              mdc_correlation text,
              recorded_at timestamptz not null default now());
            create table if not exists spikec.agent_outbox (
              event_id text primary key,
              instance_id text not null,
              event_type text not null,
              payload text not null,
              acked boolean not null default false,
              created_at timestamptz not null default now());
            create table if not exists spikec.external_sink (
              idem_key text primary key,
              thread_id text not null,
              node text not null,
              payload text not null,
              created_at timestamptz not null default now());
            """;

    @Inject
    DataSource dataSource;

    public void init() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(SCHEMA_SQL);
        } catch (SQLException e) {
            throw new IllegalStateException("schema spikec could not be created", e);
        }
    }

    // ---- registry -----------------------------------------------------------------------------------------------

    public void insertInstance(String bridge, Ctx ctx, String status) {
        update("insert into spikec.bridge_instance(instance_id, bridge, tenant, user_id, correlation_id, deadline, "
                        + "budget, topic, thread_id, status) values (?,?,?,?,?,?,?,?,?,?)",
                ctx.instanceId(), bridge, ctx.tenant(), ctx.user(), ctx.correlationId(), ctx.deadline(), ctx.budget(),
                ctx.topic(), ctx.threadId().value(), status);
    }

    public void setEngineId(String instanceId, String engineId) {
        update("update spikec.bridge_instance set engine_id = ?, updated_at = now() where instance_id = ?",
                engineId, instanceId);
    }

    public void setInstanceStatus(String instanceId, String status) {
        update("update spikec.bridge_instance set status = ?, updated_at = now() where instance_id = ?", status,
                instanceId);
    }

    public Map<String, Object> instance(String instanceId) {
        return firstRow("select * from spikec.bridge_instance where instance_id = ?", instanceId);
    }

    /** Registry rows that were started and may still be running in the engine. */
    public List<Map<String, Object>> instancesToRecover() {
        return rows("select * from spikec.bridge_instance where status in ('STARTING','RUNNING') "
                + "and engine_id is not null order by updated_at");
    }

    // ---- agent run ----------------------------------------------------------------------------------------------

    /** Records what the agent received. Called by the agent entry point before the graph runs. */
    public void noteReceived(String bridge, Ctx ctx, String headerCorrelation, String status) {
        update("insert into spikec.agent_run(thread_id, instance_id, bridge, status, received_ctx, "
                        + "received_header_correlation) values (?,?,?,?,?,?) on conflict (thread_id) do update set "
                        + "status = excluded.status, updated_at = now()",
                ctx.threadId().value(), ctx.instanceId(), bridge, status, ctx.toJson(), headerCorrelation);
    }

    public void setRunStatus(String threadId, String status) {
        update("update spikec.agent_run set status = ?, updated_at = now() where thread_id = ?", status, threadId);
    }

    public Map<String, Object> run(String threadId) {
        return firstRow("select * from spikec.agent_run where thread_id = ?", threadId);
    }

    public Map<String, Object> runByInstance(String instanceId) {
        return firstRow("select * from spikec.agent_run where instance_id = ?", instanceId);
    }

    public List<Map<String, Object>> runsToRecover(String bridge) {
        return rows("select * from spikec.agent_run where bridge = ? and status in ('RUNNING','RESUMING') "
                + "order by updated_at", bridge);
    }

    // ---- outbox (bridge C callbacks) -----------------------------------------------------------------------------

    public void outboxAdd(String eventId, String instanceId, String type, String payload) {
        update("insert into spikec.agent_outbox(event_id, instance_id, event_type, payload) values (?,?,?,?) "
                + "on conflict (event_id) do nothing", eventId, instanceId, type, payload);
    }

    /** Unacknowledged callbacks, oldest first. */
    public List<Map<String, Object>> outboxPending() {
        return rows("select * from spikec.agent_outbox where not acked order by created_at");
    }

    /** The workflow acknowledges a callback after its listen consumed the event. */
    public void outboxAck(String instanceId, String type) {
        update("update spikec.agent_outbox set acked = true where instance_id = ? and event_type = ?", instanceId,
                type);
    }

    public long outboxPendingCount(String instanceId) {
        return count("select count(*) from spikec.agent_outbox where instance_id = ? and not acked", instanceId);
    }

    // ---- steps and effects --------------------------------------------------------------------------------------

    public void recordStep(String threadId, String node, Ctx ctx, String mdcCorrelation) {
        update("insert into spikec.agent_step(thread_id, node, correlation_in_state, mdc_correlation) "
                + "values (?,?,?,?)", threadId, node, ctx.correlationId(), mdcCorrelation);
    }

    public List<Map<String, Object>> steps(String threadId) {
        return rows("select node, correlation_in_state, mdc_correlation from spikec.agent_step where thread_id = ? "
                + "order by id", threadId);
    }

    /** Writes the simulated external effect. Returns true when the row was new, false when the key already existed. */
    public boolean recordEffect(String idemKey, String threadId, String node, String payload) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "insert into spikec.external_sink(idem_key, thread_id, node, payload) values (?,?,?,?) "
                                + "on conflict (idem_key) do nothing")) {
            ps.setString(1, idemKey);
            ps.setString(2, threadId);
            ps.setString(3, node);
            ps.setString(4, payload);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public long effectCount(String threadId) {
        return count("select count(*) from spikec.external_sink where thread_id = ?", threadId);
    }

    public long effectCountAll() {
        return count("select count(*) from spikec.external_sink");
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    private void update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private long count(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private Map<String, Object> firstRow(String sql, Object... args) {
        List<Map<String, Object>> rows = rows(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                var meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }
}
