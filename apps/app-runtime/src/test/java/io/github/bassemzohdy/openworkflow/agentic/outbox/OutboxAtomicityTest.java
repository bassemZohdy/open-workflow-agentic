package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.ACTOR;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.FAIL_AT_COMMIT;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.FAIL_AT_INSERT;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.count;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.current;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.move;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.moveTask;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newInstance;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTask;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.outboxRows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcTaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Each change of an instance or an agent task writes its history row and its outbox row in one transaction (WP-105b).
 * A change that fails, at its insert or at commit, leaves neither row and does not change the row it was for.
 */
@QuarkusTest
@TestProfile(OutboxProfile.class)
class OutboxAtomicityTest {

    @Inject
    JdbcInstanceStore instances;

    @Inject
    JdbcTaskStore tasks;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void installTriggers() {
        OutboxFixture.installFailureTriggers(dataSource);
    }

    @AfterEach
    void removeTriggers() {
        OutboxFixture.removeFailureTriggers(dataSource);
    }

    @Test
    void aStartAndEachLaterChangeWriteExactlyOneOutboxRowAsTheirHistoryRow() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        assertEquals(1, outboxRows(dataSource, id.value()), "the start writes one row");
        InstanceRecord running = move(instances, current(instances, tenant, id), InstanceStatus.RUNNING);
        assertEquals(2, outboxRows(dataSource, id.value()), "the change writes one more");

        assertEquals(2L, count(dataSource, """
                select count(*) from task.outbox o join task.instance_event h
                  on o.aggregate_type = 'INSTANCE' and h.instance_id = o.aggregate_id and h.version = o.aggregate_version
                 where o.aggregate_id = ?
                """, id.value()), "every outbox row has its history row, in the same version");
        assertEquals("instance.created", eventType(id.value(), 0L));
        assertEquals("instance.status_changed", eventType(id.value(), running.version()));
        assertEquals(running.version(), count(dataSource,
                "select max(aggregate_version) from task.outbox where aggregate_id = ?", id.value()));
    }

    @Test
    void aSameStatusChangeIsRecordedAsUpdated() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        InstanceRecord row = current(instances, tenant, id);
        InstanceRecord withEngine = row.successor(Instant.now(), InstanceStatus.STARTING, "eng-" + id.value(), 0,
                null, null);
        instances.replace(withEngine, row.version(), "engine.instance.prepared", ACTOR);
        assertEquals("instance.updated", eventType(id.value(), 1L));
    }

    @Test
    void aTransitionFailingAtInsertLeavesNoOutboxRowAndNoChange() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        InstanceRecord row = current(instances, tenant, id);
        InstanceRecord next = row.successor(Instant.now(), InstanceStatus.RUNNING, "eng-" + id.value(), 0, null,
                null);

        assertThrows(StoreUnavailableException.class, () -> instances.replace(next, row.version(), FAIL_AT_INSERT,
                ACTOR));

        assertEquals(InstanceStatus.STARTING, current(instances, tenant, id).status());
        assertEquals(0L, current(instances, tenant, id).version());
        assertEquals(1L, instances.history(id).size(), "no history row for the failed change");
        assertEquals(1L, outboxRows(dataSource, id.value()), "only the start's row exists");
    }

    @Test
    void aTransitionFailingAtCommitLeavesNoOutboxRowAndNoChange() {
        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        InstanceRecord row = current(instances, tenant, id);
        InstanceRecord next = row.successor(Instant.now(), InstanceStatus.RUNNING, "eng-" + id.value(), 0, null,
                null);

        assertThrows(StoreUnavailableException.class, () -> instances.replace(next, row.version(), FAIL_AT_COMMIT,
                ACTOR));

        assertEquals(InstanceStatus.STARTING, current(instances, tenant, id).status());
        assertEquals(1L, instances.history(id).size());
        assertEquals(1L, outboxRows(dataSource, id.value()));
    }

    @Test
    void anAgentTaskChangeIsAtomicWithItsOutboxRow() {
        String tenant = newTenant();
        Ulid instance = newInstance(instances, tenant);
        AgentTaskRecord task = newTask(tasks, tenant, instance, "alice");
        assertEquals(1L, outboxRows(dataSource, task.taskId().value()), "the insert writes one row");
        assertEquals("agent_task.created", eventType(task.taskId().value(), 0L));

        AgentTaskRecord running = moveTask(tasks, task, AgentTaskStatus.RUNNING);
        assertEquals(2L, outboxRows(dataSource, task.taskId().value()));
        assertEquals(running.version(), count(dataSource,
                "select max(aggregate_version) from task.outbox where aggregate_id = ?", task.taskId().value()));

        AgentTaskRecord failedNext = new AgentTaskRecord(running.taskId(), tenant, running.owner(),
                instance.value(), running.threadId(), running.contract(), AgentTaskStatus.FAILED,
                running.version() + 1);
        long before = running.version();
        assertThrows(StoreUnavailableException.class, () -> tasks.replace(failedNext, before));

        assertEquals(AgentTaskStatus.RUNNING, tasks.find(task.taskId()).orElseThrow().status());
        assertEquals(2L, outboxRows(dataSource, task.taskId().value()), "the refused change wrote no row");
        assertEquals(2L, count(dataSource,
                "select count(*) from task.agent_task_event where agent_task_id = ?", task.taskId().value()));
    }

    @Test
    void aTaskWithoutItsInstanceWritesNothing() {
        String tenant = newTenant();
        Ulid missing = OutboxFixture.IDS.next();
        assertThrows(IllegalStateException.class, () -> newTask(tasks, tenant, missing, "alice"));
        assertEquals(0L, outboxRows(dataSource, missing.value()));
    }

    private String eventType(String aggregateId, long version) {
        return OutboxFixture.eventType(dataSource, aggregateId, version);
    }
}
