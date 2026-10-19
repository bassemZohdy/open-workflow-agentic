package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.IllegalTransitionException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcTaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StaleVersionException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The agent_task table as a TaskStore, on Postgres: ownership, uniqueness, optimistic locking and history. */
@QuarkusTest
class JdbcTaskStoreTest {

    @Inject
    JdbcTaskStore tasks;

    @Inject
    JdbcInstanceStore instances;

    @Inject
    DataSource dataSource;

    private static final UlidGenerator IDS = UlidGenerator.shared();

    @Test
    void aTaskIsFoundByItsIdAndByItsThreadAndHoldsItsContract() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord created = tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L));

        assertEquals(created, tasks.find(created.taskId()).orElseThrow());
        assertEquals(created, tasks.findByThread(created.threadId()).orElseThrow());
    }

    @Test
    void aTaskNeedsAnInstanceOfTheSameTenant() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord otherTenant = record(LifecycleSupport.newTenant(), instance, "alice",
                AgentTaskStatus.PENDING, 0L);
        assertThrows(IllegalStateException.class, () -> tasks.insert(otherTenant));
        assertThrows(IllegalStateException.class,
                () -> tasks.insert(record(tenant, IDS.next(), "alice", AgentTaskStatus.PENDING, 0L)));
    }

    @Test
    void aNewTaskMustBePendingAtVersionZero() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        assertThrows(IllegalStateException.class,
                () -> tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.RUNNING, 0L)));
        assertThrows(IllegalStateException.class,
                () -> tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 1L)));
    }

    @Test
    void twoTasksOfOneUserAndInstanceShareAThreadAndAreRefused() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L));
        assertThrows(IllegalStateException.class,
                () -> tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L)));
    }

    @Test
    void aThreadIsOwnedByItsDerivedScopeOnly() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord bob = tasks.insert(record(tenant, instance, "bob", AgentTaskStatus.PENDING, 0L));
        ThreadId aliceThread = ThreadId.derive(tenant, "alice", instance.value());
        assertTrue(tasks.findByThread(aliceThread).isEmpty(), "another user's thread is not found");
        assertEquals(bob, tasks.findByThread(bob.threadId()).orElseThrow());
    }

    @Test
    void theStatusFollowsTheStateMachineAndEveryChangeIsInHistory() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord task = tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L));
        AgentTaskRecord v = task;
        for (AgentTaskStatus next : new AgentTaskStatus[] {
                AgentTaskStatus.RUNNING, AgentTaskStatus.WAITING_FOR_HUMAN, AgentTaskStatus.RESUMING,
                AgentTaskStatus.RUNNING, AgentTaskStatus.COMPLETED}) {
            v = tasks.replace(with(v, next), v.version());
        }
        assertEquals(AgentTaskStatus.COMPLETED, tasks.find(task.taskId()).orElseThrow().status());
        assertEquals(5L, tasks.find(task.taskId()).orElseThrow().version());
        assertEquals(6L, LifecycleSupport.count(dataSource,
                "select count(*) from task.agent_task_event where agent_task_id = ?", task.taskId().value()),
                "the insert and five changes");
    }

    @Test
    void anIllegalStatusChangeIsRefusedAndWritesNothing() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord task = tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L));
        AgentTaskRecord running = tasks.replace(with(task, AgentTaskStatus.RUNNING), 0L);
        AgentTaskRecord completed = tasks.replace(with(running, AgentTaskStatus.COMPLETED), 1L);

        AgentTaskRecord illegal = with(completed, AgentTaskStatus.RUNNING);
        long completedVersion = completed.version();
        assertThrows(IllegalTransitionException.class, () -> tasks.replace(illegal, completedVersion));
        assertEquals(AgentTaskStatus.COMPLETED, tasks.find(task.taskId()).orElseThrow().status());
        assertEquals(3L, LifecycleSupport.count(dataSource,
                "select count(*) from task.agent_task_event where agent_task_id = ?", task.taskId().value()));
    }

    @Test
    void aStaleReplaceIsRefused() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord task = tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L));
        AgentTaskRecord running = tasks.replace(with(task, AgentTaskStatus.RUNNING), 0L);

        AgentTaskRecord late = with(task, AgentTaskStatus.FAILED);
        assertThrows(StaleVersionException.class, () -> tasks.replace(late, 0L));
        assertEquals(running.version(), tasks.find(task.taskId()).orElseThrow().version());
    }

    @Test
    void theContractAndTheOwnerMayNotBeChangedByAReplace() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord task = tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L));
        // Same id and owner (so the record is valid), but a wider tool list: the store must refuse it.
        AgentTaskContract widened = new AgentTaskContract(task.taskId(), "goal for " + tenant,
                Set.of("tool:search", "tool:other"), Set.of("scope:notes"), RiskTier.LOW,
                new ExecutionLimits(10, Duration.ofMinutes(5), 1_000_000L), task.owner(),
                task.contract().delegationGrantId());
        AgentTaskRecord changed = new AgentTaskRecord(task.taskId(), tenant, task.owner(), instance.value(),
                task.threadId(), widened, AgentTaskStatus.RUNNING, 1L);
        assertThrows(IllegalStateException.class, () -> tasks.replace(changed, 0L));
    }

    @Test
    void aReplaceWithTheWrongNextVersionIsRefused() {
        String tenant = LifecycleSupport.newTenant();
        Ulid instance = newInstance(tenant);
        AgentTaskRecord task = tasks.insert(record(tenant, instance, "alice", AgentTaskStatus.PENDING, 0L));
        AgentTaskRecord skipped = new AgentTaskRecord(task.taskId(), tenant, task.owner(), instance.value(),
                task.threadId(), task.contract(), AgentTaskStatus.RUNNING, 5L);
        assertThrows(IllegalStateException.class, () -> tasks.replace(skipped, 0L));
    }

    private Ulid newInstance(String tenant) {
        Ulid id = IDS.next();
        StartRequest request = new StartRequest(tenant, new Subject(SubjectKind.USER, "alice"),
                new DefinitionRef("agentic/research-and-approve", "0.1.0"), Map.of("topic", "t"), "key-" + id);
        instances.insertIfAbsent(InstanceRecord.starting(id, request, Instant.now()), "start.requested");
        return id;
    }

    private static AgentTaskRecord record(String tenant, Ulid instance, String user, AgentTaskStatus status,
                                          long version) {
        Subject owner = new Subject(SubjectKind.USER, user);
        AgentTaskContract contract = contract(tenant, user, Set.of("tool:search"));
        return new AgentTaskRecord(contract.id(), tenant, owner, instance.value(),
                ThreadId.derive(tenant, user, instance.value()), contract, status, version);
    }

    private static AgentTaskContract contract(String tenant, String user, Set<String> tools) {
        return new AgentTaskContract(IDS.next(), "goal for " + tenant, tools, Set.of("scope:notes"),
                RiskTier.LOW, new ExecutionLimits(10, Duration.ofMinutes(5), 1_000_000L),
                new Subject(SubjectKind.USER, user), IDS.next());
    }

    /** The same task with another status and the next version. */
    private static AgentTaskRecord with(AgentTaskRecord task, AgentTaskStatus status) {
        return new AgentTaskRecord(task.taskId(), task.tenant(), task.owner(), task.workflowInstance(),
                task.threadId(), task.contract(), status, task.version() + 1);
    }
}
