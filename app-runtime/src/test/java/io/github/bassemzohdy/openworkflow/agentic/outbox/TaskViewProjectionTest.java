package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.count;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.current;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.move;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.moveTask;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newInstance;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTask;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.relay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcTaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxEntry;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewFilter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewProjector;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewQuery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The view projector (WP-105b): what a row holds, the waiting flag, idempotence and the refusal of early events. */
@QuarkusTest
@TestProfile(OutboxProfile.class)
class TaskViewProjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    JdbcInstanceStore instances;

    @Inject
    JdbcTaskStore tasks;

    @Inject
    TaskViewProjector projector;

    @Inject
    TaskViewQuery views;

    @Inject
    DataSource dataSource;

    @Test
    void theViewRowFollowsTheRegistryAndItsLastEvent() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        var relay = relay(dataSource, projector);
        relay.drainAll(10_000);

        TaskViewRow created = rowOf(tenant, id);
        assertEquals(tenant, created.tenant());
        assertEquals("USER", created.ownerKind());
        assertEquals("alice", created.ownerId());
        assertEquals("agentic/research-and-approve", created.definitionId());
        assertEquals("0.1.0", created.definitionVersion());
        assertEquals("STARTING", created.instanceStatus());
        assertEquals(0L, created.instanceVersion());
        assertEquals(null, created.agentTaskId());
        assertFalse(created.waitingForHuman());
        assertEquals("start.requested", created.lastReason());
        assertEquals(created.lastTransitionAt(), created.updatedAt());
        assertEquals(outboxIdOf(id, 0L), created.lastOutboxId());

        move(instances, current(instances, tenant, id), InstanceStatus.RUNNING, "engine.start.accepted");
        relay.drainAll(10_000);
        TaskViewRow running = rowOf(tenant, id);
        assertEquals("RUNNING", running.instanceStatus());
        assertEquals(1L, running.instanceVersion());
        assertEquals("engine.start.accepted", running.lastReason());
        assertEquals(outboxIdOf(id, 1L), running.lastOutboxId());
        assertTrue(running.lastOutboxId() > created.lastOutboxId());
    }

    @Test
    void waitingForHumanFollowsTheInstanceAndTheCurrentAgentTask() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        var relay = relay(dataSource, projector);
        var row = move(instances, current(instances, tenant, id), InstanceStatus.RUNNING);
        AgentTaskRecord task = newTask(tasks, tenant, id, "alice");
        relay.drainAll(10_000);
        assertFalse(rowOf(tenant, id).waitingForHuman());

        AgentTaskRecord running = moveTask(tasks, task, AgentTaskStatus.RUNNING);
        moveTask(tasks, running, AgentTaskStatus.WAITING_FOR_HUMAN);
        relay.drainAll(10_000);
        TaskViewRow waiting = rowOf(tenant, id);
        assertTrue(waiting.waitingForHuman());
        assertEquals(task.taskId().value(), waiting.agentTaskId());
        assertEquals("WAITING_FOR_HUMAN", waiting.agentTaskStatus());
        assertEquals("RUNNING", waiting.instanceStatus());

        AgentTaskRecord resuming = moveTask(tasks, tasks.find(task.taskId()).orElseThrow(),
                AgentTaskStatus.RESUMING);
        relay.drainAll(10_000);
        assertFalse(rowOf(tenant, id).waitingForHuman(), "a resumed task is no longer waiting");

        move(instances, row, InstanceStatus.WAITING);
        relay.drainAll(10_000);
        assertTrue(rowOf(tenant, id).waitingForHuman(), "an instance that waits is waiting for a person too");
        assertEquals(resuming.version(), tasks.find(task.taskId()).orElseThrow().version());
    }

    @Test
    void aDuplicateOrOlderEventChangesNothing() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        move(instances, current(instances, tenant, id), InstanceStatus.RUNNING);
        OutboxFixture.RecordingConsumer recording = new OutboxFixture.RecordingConsumer();
        relay(dataSource, projector, recording).drainAll(10_000);
        TaskViewRow before = rowOf(tenant, id);

        for (OutboxEvent event : recording.forPartition(id.value())) {
            Transactions.inTransaction(dataSource, connection -> {
                projector.apply(connection, event);
                return null;
            });
        }
        assertEquals(before, rowOf(tenant, id), "applying every event again changes nothing");
    }

    @Test
    void anAgentEventForAnInstanceNotInTheViewYetIsRefusedSoItsRelayRetries() throws Exception {
        String partition = OutboxFixture.IDS.next().value();
        OutboxEvent early = new OutboxEvent(999_999_999L, OutboxEntry.AggregateType.AGENT_TASK,
                OutboxFixture.IDS.next().value(), 0L, partition, "agent_task.created",
                JSON.readTree("{\"status\":\"PENDING\",\"reason\":\"task.created\",\"tenant\":\"t\"}"),
                Instant.now(), 0);
        assertThrows(IllegalStateException.class, () -> Transactions.inTransaction(dataSource, connection -> {
            projector.apply(connection, early);
            return null;
        }));
        assertEquals(0L, count(dataSource, "select count(*) from task.task_view where instance_id = ?", partition));
    }

    private TaskViewRow rowOf(String tenant, Ulid id) {
        List<TaskViewRow> rows = views.list(TaskViewFilter.forTenant(tenant), PageRequest.firstPage()).items();
        return rows.stream().filter(r -> r.instanceId().equals(id.value())).findFirst().orElseThrow();
    }

    private long outboxIdOf(Ulid id, long version) {
        return count(dataSource, "select id from task.outbox where aggregate_id = ? and aggregate_version = ?",
                id.value(), version);
    }
}
