package io.github.bassemzohdy.openworkflow.agentic.outbox;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcTaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxConsumer;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxRelay;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.RelaySettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sql.DataSource;

/** Shared helpers for the outbox tests. Each test uses its own tenant, so tests do not see each other's rows. */
final class OutboxFixture {

    static final UlidGenerator IDS = UlidGenerator.shared();
    static final Subject ACTOR = InstanceService.RUNTIME_ACTOR;
    static final String DEFINITION_ID = "agentic/research-and-approve";
    static final String DEFINITION_VERSION = "0.1.0";

    /** A reason the failure trigger refuses (see {@link #installFailureTriggers}). */
    static final String FAIL_AT_INSERT = "test.inject-failure";
    /** A reason the commit-time failure trigger refuses. */
    static final String FAIL_AT_COMMIT = "test.inject-commit-failure";

    private OutboxFixture() {
    }

    static String newTenant() {
        return "tenant-" + UUID.randomUUID();
    }

    /** A tenant whose name starts with "poison-", for the consumer that fails on purpose. */
    static String newPoisonTenant() {
        return "poison-" + UUID.randomUUID();
    }

    static Ulid newInstance(JdbcInstanceStore store, String tenant) {
        Ulid id = IDS.next();
        StartRequest request = new StartRequest(tenant, new Subject(SubjectKind.USER, "alice"),
                new DefinitionRef(DEFINITION_ID, DEFINITION_VERSION), Map.of("topic", "outbox"), "key-" + id);
        store.insertIfAbsent(InstanceRecord.starting(id, request, Instant.now()), "start.requested");
        return id;
    }

    static InstanceRecord current(JdbcInstanceStore store, String tenant, Ulid id) {
        return store.find(tenant, id).orElseThrow();
    }

    /** Moves an instance to a status, keeping its engine id, with the given reason. */
    static InstanceRecord move(JdbcInstanceStore store, InstanceRecord row, InstanceStatus to, String reason) {
        String engineId = row.engineInstanceId() == null ? "eng-" + row.id().value() : row.engineInstanceId();
        InstanceRecord next = row.successor(Instant.now(), to, engineId, row.startAttempts(), row.lastError(),
                row.waitDeadline());
        return store.replace(next, row.version(), reason, ACTOR);
    }

    static InstanceRecord move(JdbcInstanceStore store, InstanceRecord row, InstanceStatus to) {
        return move(store, row, to, "test.transition");
    }

    static AgentTaskRecord newTask(JdbcTaskStore tasks, String tenant, Ulid instance, String user) {
        Subject owner = new Subject(SubjectKind.USER, user);
        AgentTaskContract contract = new AgentTaskContract(IDS.next(), "goal for " + tenant, Set.of("tool:search"),
                Set.of("scope:notes"), RiskTier.LOW, new ExecutionLimits(10, Duration.ofMinutes(5), 1_000_000L),
                owner, IDS.next());
        AgentTaskRecord record = new AgentTaskRecord(contract.id(), tenant, owner, instance.value(),
                ThreadId.derive(tenant, user, instance.value()), contract, AgentTaskStatus.PENDING, 0L);
        return tasks.insert(record);
    }

    static AgentTaskRecord moveTask(JdbcTaskStore tasks, AgentTaskRecord task, AgentTaskStatus to) {
        AgentTaskRecord next = new AgentTaskRecord(task.taskId(), task.tenant(), task.owner(),
                task.workflowInstance(), task.threadId(), task.contract(), to, task.version() + 1);
        return tasks.replace(next, task.version());
    }

    /** The outbox rows of one aggregate (instance id or task id). */
    static long outboxRows(DataSource dataSource, String aggregateId) {
        return count(dataSource, "select count(*) from task.outbox where aggregate_id = ?", aggregateId);
    }

    /** The text of a single-column query with one long parameter, or null. */
    static String text(DataSource dataSource, String sql, long param) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed", e);
        }
    }

    /** The event type of the outbox row for one aggregate version. */
    static String eventType(DataSource dataSource, String aggregateId, long version) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select event_type from task.outbox where aggregate_id = ? and aggregate_version = ?")) {
            statement.setString(1, aggregateId);
            statement.setLong(2, version);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed", e);
        }
    }

    /** Runs a count query with parameters and returns its single number. */
    static long count(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("count failed", e);
        }
    }

    static OutboxRelay relay(DataSource dataSource, OutboxConsumer... consumers) {
        return new OutboxRelay(dataSource, List.of(consumers), new RelaySettings(20, 5, Duration.ZERO,
                Duration.ZERO, Duration.ofMillis(100)));
    }

    /** Runs a statement with parameters; returns the number of rows it changed. */
    static int update(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("statement failed", e);
        }
    }

    static void run(DataSource dataSource, String... statements) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("statement failed", e);
        }
    }

    /**
     * Two triggers on {@code task.outbox} that refuse some rows. The first refuses at insert, so the change that wrote
     * the row fails part way through. The second refuses at commit, after every write of the change. Both are removed by
     * {@link #removeFailureTriggers}.
     */
    static void installFailureTriggers(DataSource dataSource) {
        run(dataSource,
                """
                create or replace function task.test_fail_outbox_insert() returns trigger language plpgsql as $$
                begin
                    if new.payload->>'reason' = 'test.inject-failure'
                       or (new.aggregate_type = 'AGENT_TASK' and new.payload->>'status' = 'FAILED') then
                        raise exception 'injected failure at insert' using errcode = 'P0001';
                    end if;
                    return new;
                end $$
                """,
                "drop trigger if exists test_fail_outbox_insert on task.outbox",
                """
                create trigger test_fail_outbox_insert before insert on task.outbox
                    for each row execute function task.test_fail_outbox_insert()
                """,
                """
                create or replace function task.test_fail_outbox_commit() returns trigger language plpgsql as $$
                begin
                    if new.payload->>'reason' = 'test.inject-commit-failure' then
                        raise exception 'injected failure at commit' using errcode = 'P0001';
                    end if;
                    return new;
                end $$
                """,
                "drop trigger if exists test_fail_outbox_commit on task.outbox",
                """
                create constraint trigger test_fail_outbox_commit after insert on task.outbox
                    deferrable initially deferred for each row execute function task.test_fail_outbox_commit()
                """);
    }

    static void removeFailureTriggers(DataSource dataSource) {
        run(dataSource,
                "drop trigger if exists test_fail_outbox_insert on task.outbox",
                "drop trigger if exists test_fail_outbox_commit on task.outbox",
                "drop function if exists task.test_fail_outbox_insert()",
                "drop function if exists task.test_fail_outbox_commit()");
    }

    /** Records every event a relay hands it, in the order of the calls. */
    static final class RecordingConsumer implements OutboxConsumer {

        final java.util.Queue<OutboxEvent> delivered = new ConcurrentLinkedQueue<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void apply(Connection connection, OutboxEvent event) {
            delivered.add(event);
        }

        List<OutboxEvent> forPartition(String partitionKey) {
            List<OutboxEvent> events = new ArrayList<>();
            for (OutboxEvent event : delivered) {
                if (event.partitionKey().equals(partitionKey)) {
                    events.add(event);
                }
            }
            return events;
        }
    }

    /** Fails, on purpose, every event of a tenant that starts with "poison-", while {@code failing} is set. */
    static final class FlakyConsumer implements OutboxConsumer {

        final java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(true);

        @Override
        public String name() {
            return "flaky";
        }

        @Override
        public void apply(Connection connection, OutboxEvent event) {
            if (failing.get() && event.payload().path("tenant").asText().startsWith("poison-")) {
                throw new IllegalStateException("refused on purpose");
            }
        }
    }
}
