package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.executors.DefaultTaskExecutorFactory;
import io.serverlessworkflow.impl.expressions.ExpressionDescriptor;
import io.serverlessworkflow.impl.expressions.jq.JQExpressionFactory;
import io.serverlessworkflow.impl.lifecycle.*;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Real SDK execution boundaries backed by V82 in a disposable database. No production wiring or external-effect proof. */
@QuarkusTest
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowOwnerExecutionBoundaryTest {
    private static final String DOCUMENT = """
            document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
            do:
              - first:
                  set: { first: true }
              - second:
                  set: { second: true }
            """;
    @Inject DataSource data;
    @Inject JdbcInstanceStore store;
    @Inject WorkflowOwnerContextBindings bindings;
    @Inject WorkflowOwnerExecutionFence ownerFence;

    @BeforeAll void installProposal() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("db/proposed/V82__workflow_owner_lease.sql")) {
            assertNotNull(in);
            try (var c = data.getConnection(); var s = c.createStatement()) {
                s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
    private final class GuardedExpressions extends JQExpressionFactory {
        final AtomicInteger evaluated = new AtomicInteger();
        @Override public int priority(ExpressionDescriptor expression) { return 999; }
        @Override public WorkflowFilter buildFilter(ExpressionDescriptor expression, WorkflowModelFactory models) {
            var delegate = super.buildFilter(expression, models);
            return OwnerFencedWorkflowFilter.wrap((workflow, task, input) -> {
                if (task == null) evaluated.incrementAndGet();
                return delegate.apply(workflow, task, input);
            }, ownerFence::requireCurrent);
        }
    }
    private JdbcWorkflowOwnerLease.Binding register(String appId, String tenant, WorkflowInstance instance) {
        var actor = new Subject(SubjectKind.USER, "execution-boundary-test");
        var definition = new DefinitionRef("agentic-test/owner-boundary", "1.0.0");
        var request = new StartRequest(tenant, actor, definition, Map.of(), "owner-boundary-" + UUID.randomUUID());
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), request, now), "test.insert").record();
        row = store.replace(row.successor(now, InstanceStatus.RUNNING, instance.id(), 0, null, null), row.version(), "test.running", actor);
        return new JdbcWorkflowOwnerLease.Binding(tenant, row.id(), appId, instance.id(), definition);
    }

    @Test void currentOwnerPassesRootFilterAndBothTaskBoundaries() throws Exception {
        String appId = "owner-exec-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>(); var starts = new AtomicInteger();
        var expressions = new GuardedExpressions();
        String document = DOCUMENT.replace("do:\n", "input:\n  from: ${ .payload }\ndo:\n");
        try (var app = WorkflowApplication.builder().withId(appId)
                .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                .withExpressionFactory(expressions).disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onWorkflowStarted(WorkflowStartedEvent event) { bindings.bind(event.workflowContext(), claim.get()); }
                    @Override public void onTaskStarted(TaskStartedEvent event) { starts.incrementAndGet(); }
                }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML))
                    .instance(Map.of("payload", Map.of("original", "kept")));
            var binding = register(appId, tenant, instance);
            claim.set(new JdbcWorkflowOwnerLease(data).acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow().claim());
            instance.start().get(10, TimeUnit.SECONDS);
            assertEquals(1, expressions.evaluated.get()); assertEquals(2, starts.get());
        }
    }

    @Test void staleOwnerCannotEvaluateTheWorkflowInputFilter() throws Exception {
        String appId = "owner-input-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>(); var starts = new AtomicInteger();
        var expressions = new GuardedExpressions();
        String document = DOCUMENT.replace("do:\n", "input:\n  from: '${ error(\"input filter ran\") }'\ndo:\n");
        try (var app = WorkflowApplication.builder().withId(appId)
                .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                .withExpressionFactory(expressions).disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onWorkflowStarted(WorkflowStartedEvent event) { bindings.bind(event.workflowContext(), claim.get()); }
                    @Override public void onTaskStarted(TaskStartedEvent event) { starts.incrementAndGet(); }
                }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML)).instance(Map.of());
            var binding = register(appId, tenant, instance); var leases = new JdbcWorkflowOwnerLease(data);
            var first = leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow(); claim.set(first.claim());
            assertTrue(leases.release(first.claim()));
            assertTrue(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).isPresent());
            assertThrows(Exception.class, () -> instance.start().get(10, TimeUnit.SECONDS));
            assertEquals(0, expressions.evaluated.get(), "the input filter itself must never run");
            assertEquals(0, starts.get());
        }
    }

    @Test void takeoverBetweenTasksRefusesTheNextTask() throws Exception {
        String appId = "owner-tasks-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>();
        var leaseBinding = new AtomicReference<JdbcWorkflowOwnerLease.Binding>();
        var taskNames = new CopyOnWriteArrayList<String>(); var takeover = new AtomicBoolean();
        var leases = new JdbcWorkflowOwnerLease(data);
        try (var app = WorkflowApplication.builder().withId(appId)
                .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onWorkflowStarted(WorkflowStartedEvent event) { bindings.bind(event.workflowContext(), claim.get()); }
                    @Override public void onTaskStarted(TaskStartedEvent event) { taskNames.add(event.taskContext().taskName()); }
                    @Override public void onTaskCompleted(TaskCompletedEvent event) {
                        if (event.taskContext().taskName().equals("first")) {
                            if (!leases.release(claim.get())) throw new IllegalStateException("old owner was not released");
                            if (leases.acquire(leaseBinding.get(), UUID.randomUUID(), Duration.ofSeconds(15)).isEmpty())
                                throw new IllegalStateException("successor was not acquired");
                            takeover.set(true);
                        }
                    }
                }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(DOCUMENT, WorkflowFormat.YAML)).instance(Map.of());
            var binding = register(appId, tenant, instance); leaseBinding.set(binding);
            claim.set(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow().claim());
            assertThrows(Exception.class, () -> instance.start().get(10, TimeUnit.SECONDS));
            assertTrue(takeover.get()); assertEquals(List.of("first"), taskNames);
        }
    }
}
