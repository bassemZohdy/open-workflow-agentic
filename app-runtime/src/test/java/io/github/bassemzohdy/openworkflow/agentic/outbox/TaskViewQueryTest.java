package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.current;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.move;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newInstance;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.outbox.OutboxFixture.relay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxAdmin;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.OutboxMetrics;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewFilter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewPage;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewProjector;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewQuery;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.TaskViewRow;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The read repository and the lag gauges (WP-105b). */
@QuarkusTest
@TestProfile(OutboxProfile.class)
class TaskViewQueryTest {

    @Inject
    JdbcInstanceStore instances;

    @Inject
    TaskViewProjector projector;

    @Inject
    TaskViewQuery views;

    @Inject
    OutboxAdmin admin;

    @Inject
    MeterRegistry registry;

    @Inject
    DataSource dataSource;

    @Test
    void pagesAreStableAndFiltersStayInsideOneTenant() {
        String tenant = newTenant();
        List<Ulid> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(newInstance(instances, tenant));
        }
        move(instances, current(instances, tenant, ids.get(0)), InstanceStatus.RUNNING);
        move(instances, current(instances, tenant, ids.get(3)), InstanceStatus.RUNNING);
        relay(dataSource, projector).drainAll(100_000);

        TaskViewPage first = views.list(TaskViewFilter.forTenant(tenant), PageRequest.of(null, 2));
        TaskViewPage second = views.list(TaskViewFilter.forTenant(tenant), PageRequest.of(first.nextCursor(), 2));
        TaskViewPage third = views.list(TaskViewFilter.forTenant(tenant), PageRequest.of(second.nextCursor(), 2));
        assertEquals(2, first.items().size());
        assertEquals(2, second.items().size());
        assertEquals(1, third.items().size());
        assertNull(third.nextCursor(), "the last page has no cursor");

        List<String> listed = new ArrayList<>();
        first.items().forEach(r -> listed.add(r.instanceId()));
        second.items().forEach(r -> listed.add(r.instanceId()));
        third.items().forEach(r -> listed.add(r.instanceId()));
        List<String> expected = ids.stream().map(Ulid::value).sorted().toList();
        assertEquals(expected, listed, "pages hold every row once, in instance id order");

        assertEquals(2, views.list(new TaskViewFilter(tenant, InstanceStatus.RUNNING, null),
                PageRequest.firstPage()).items().size());
        assertEquals(5, views.list(new TaskViewFilter(tenant, null, OutboxFixture.DEFINITION_ID),
                PageRequest.firstPage()).items().size());
        assertEquals(0, views.list(new TaskViewFilter(tenant, null, "other/definition"),
                PageRequest.firstPage()).items().size());
        assertEquals(0, views.list(TaskViewFilter.forTenant(newTenant()), PageRequest.firstPage()).items().size(),
                "another tenant sees none of these rows");
    }

    @Test
    void aCursorThatIsNotACursorIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> views.list(TaskViewFilter.forTenant(newTenant()), PageRequest.of("not a cursor!", 2)));
    }

    @Test
    void theLagIsExposedAsGaugesAndCountsWhatIsUnpublished() throws InterruptedException {
        assertNotNull(registry.find(OutboxMetrics.UNPUBLISHED).gauge(), "the count gauge is registered");
        assertNotNull(registry.find(OutboxMetrics.OLDEST_AGE).gauge(), "the age gauge is registered");
        assertNotNull(registry.find(OutboxMetrics.FAILED).gauge(), "the failed gauge is registered");

        String tenant = newTenant();
        Ulid id = newInstance(instances, tenant);
        Thread.sleep(1_100);
        OutboxAdmin.Lag lag = admin.lag();
        assertTrue(lag.unpublished() >= 1, "the unpublished start is counted");
        assertTrue(lag.oldestUnpublishedAgeSeconds() >= 1.0, "its age is measured");
        assertTrue(registry.get(OutboxMetrics.UNPUBLISHED).gauge().value() >= 1.0);
        assertTrue(registry.get(OutboxMetrics.OLDEST_AGE).gauge().value() >= 1.0);

        relay(dataSource, projector).drainAll(100_000);
        assertTrue(views.list(TaskViewFilter.forTenant(tenant), PageRequest.firstPage()).items()
                .stream().map(TaskViewRow::instanceId).anyMatch(id.value()::equals));
    }
}
