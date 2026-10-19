package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.current;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.move;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.moveTask;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newInstance;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTask;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.relay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcTaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewProjector;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewQuery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewRebuilder;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewRow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The rebuild (WP-105b): deleting every view row and rebuilding from the registry and history gives exactly the rows the
 * live projector produced, field by field. Events that are not published yet are in neither.
 */
@QuarkusTest
@TestProfile(OutboxProfile.class)
class TaskViewRebuildTest {

    @Inject
    JdbcInstanceStore instances;

    @Inject
    JdbcTaskStore tasks;

    @Inject
    TaskViewProjector projector;

    @Inject
    TaskViewQuery views;

    @Inject
    TaskViewRebuilder rebuilder;

    @Inject
    DataSource dataSource;

    @Test
    void deletingEveryRowAndRebuildingGivesTheLiveRowsExactly() {
        String tenant = newTenant();
        var relay = relay(dataSource, projector);

        Ulid waiting = newInstance(instances, tenant);
        InstanceRecord w = move(instances, current(instances, tenant, waiting), InstanceStatus.RUNNING);
        AgentTaskRecord task = newTask(tasks, tenant, waiting, "alice");
        AgentTaskRecord running = moveTask(tasks, task, AgentTaskStatus.RUNNING);
        moveTask(tasks, running, AgentTaskStatus.WAITING_FOR_HUMAN);
        move(instances, w, InstanceStatus.WAITING);

        Ulid done = newInstance(instances, tenant);
        InstanceRecord d = move(instances, current(instances, tenant, done), InstanceStatus.RUNNING);
        move(instances, d, InstanceStatus.COMPLETED);

        for (int i = 0; i < 3; i++) {
            newInstance(instances, tenant);
        }
        relay.drainAll(100_000);

        // Published events are in the view; a change still in the outbox is in neither the view nor the rebuild.
        Ulid pending = newInstance(instances, tenant);
        move(instances, current(instances, tenant, pending), InstanceStatus.RUNNING);

        List<TaskViewRow> live = views.snapshot();
        assertTrue(live.stream().anyMatch(r -> r.instanceId().equals(waiting.value()) && r.waitingForHuman()));
        assertTrue(live.stream().anyMatch(r -> r.instanceId().equals(done.value())
                && r.instanceStatus().equals("COMPLETED")));
        assertFalse(live.stream().anyMatch(r -> r.instanceId().equals(pending.value())),
                "an unpublished start is not in the view");

        OutboxFixture.run(dataSource, "delete from task.task_view");
        long rebuilt = rebuilder.rebuild();
        List<TaskViewRow> afterRebuild = views.snapshot();

        assertEquals(idsOf(live), idsOf(afterRebuild), "the rebuild has the same instances as the live view");
        assertEquals(live.size(), rebuilt, "the rebuild counts the same rows");
        assertEquals(live.size(), afterRebuild.size());
        for (int i = 0; i < live.size(); i++) {
            assertEquals(live.get(i), afterRebuild.get(i), "row " + live.get(i).instanceId() + " differs");
        }

        // Once the pending start is delivered, the live view catches up and still equals a fresh rebuild.
        relay.drainAll(100_000);
        List<TaskViewRow> caughtUp = views.snapshot();
        rebuilder.rebuild();
        assertEquals(caughtUp, views.snapshot());
        assertTrue(caughtUp.stream().anyMatch(r -> r.instanceId().equals(pending.value())));
    }

    private static Set<String> idsOf(List<TaskViewRow> rows) {
        return rows.stream().map(TaskViewRow::instanceId).collect(Collectors.toCollection(TreeSet::new));
    }
}
