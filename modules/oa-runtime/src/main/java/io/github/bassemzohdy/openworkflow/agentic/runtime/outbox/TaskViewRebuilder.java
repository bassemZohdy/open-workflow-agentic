package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Recreates {@code task.task_view} from the platform's records (WP-105b). Nothing in the view is read back to build
 * it: the instance and agent task history (with the outbox rows that mirror it) is the input.
 *
 * <p><b>Same result as the projector.</b> The rebuild takes the events that are already published, the same set the
 * projector has applied, and folds them the way {@link TaskViewProjector} does. So after a rebuild, and when no
 * events are in flight, the view equals what the live projector produced, column by column. A row whose event is
 * not yet published (in backoff, failed, or waiting for its relay) is not in the rebuilt view, just as it is not in
 * the live one.
 *
 * <p><b>Locking.</b> The rebuild takes {@code share row exclusive} locks on the outbox and the view for its
 * transaction. Relays cannot mark rows published and the projector cannot write during the rebuild, so the view is
 * consistent when it commits. Readers are not blocked. The rebuild is a maintenance operation; it is not meant to run
 * on every request.
 */
@ApplicationScoped
public class TaskViewRebuilder {

    private static final String REBUILD = """
            insert into task.task_view (instance_id, tenant, owner_kind, owner_id, definition_id, definition_version,
                instance_status, instance_version, agent_task_id, agent_task_status, agent_outbox_id,
                last_reason, last_transition_at, last_outbox_id, updated_at)
            with published as (
                select o.id as outbox_id, o.partition_key as instance_id, o.aggregate_type,
                       o.aggregate_id as subject_id, o.aggregate_version as version, o.occurred_at,
                       h.reason, h.to_status as status
                  from task.outbox o
                  join task.instance_event h
                    on o.aggregate_type = 'INSTANCE' and h.instance_id = o.aggregate_id
                   and h.version = o.aggregate_version
                 where o.published_at is not null
                union all
                select o.id, o.partition_key, o.aggregate_type,
                       o.aggregate_id, o.aggregate_version, o.occurred_at,
                       h.reason, h.to_status
                  from task.outbox o
                  join task.agent_task_event h
                    on o.aggregate_type = 'AGENT_TASK' and h.agent_task_id = o.aggregate_id
                   and h.version = o.aggregate_version
                 where o.published_at is not null
            ),
            last_event as (
                select distinct on (instance_id) instance_id, outbox_id, reason, occurred_at
                  from published
                 order by instance_id, outbox_id desc
            ),
            last_instance as (
                select distinct on (instance_id) instance_id, version, status
                  from published
                 where aggregate_type = 'INSTANCE'
                 order by instance_id, version desc
            ),
            last_agent as (
                select distinct on (instance_id) instance_id, subject_id, status, outbox_id
                  from published
                 where aggregate_type = 'AGENT_TASK'
                 order by instance_id, outbox_id desc
            )
            select i.id, i.tenant, i.owner_kind, i.owner_id, i.definition_id, i.definition_version,
                   li.status, li.version, la.subject_id, la.status, la.outbox_id,
                   le.reason, le.occurred_at, le.outbox_id, le.occurred_at
              from last_instance li
              join task.instance i on i.id = li.instance_id
              join last_event le on le.instance_id = li.instance_id
              left join last_agent la on la.instance_id = li.instance_id
            """;

    private final DataSource dataSource;

    @Inject
    public TaskViewRebuilder(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Deletes every view row and rebuilds them, in one transaction.
     *
     * @return the number of rows in the rebuilt view
     */
    public long rebuild() {
        return Transactions.inTransaction(dataSource, this::rebuildIn);
    }

    private long rebuildIn(Connection connection) throws SQLException {
        try (Statement lock = connection.createStatement()) {
            lock.execute("lock table task.outbox, task.task_view in share row exclusive mode");
            lock.executeUpdate("delete from task.task_view");
        }
        try (PreparedStatement insert = connection.prepareStatement(REBUILD)) {
            return insert.executeUpdate();
        }
    }
}
