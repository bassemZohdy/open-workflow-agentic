package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.execution.GovernedExecutionRoot;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.OwnerFencedJpaWriter;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.CancellablePersistenceReader;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.PendingCancellationReceiptWriter;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceOperations;
import jakarta.transaction.TransactionManager;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceReader;
import io.quarkiverse.flow.persistence.jpa.JpaInstanceWriter;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.executors.DefaultTaskExecutorFactory;
import io.serverlessworkflow.impl.expressions.ExpressionDescriptor;
import io.serverlessworkflow.impl.expressions.jq.JQExpressionFactory;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.persistence.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.function.Consumer;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
    @Inject JpaInstanceReader reader;
    @Inject JpaInstanceWriter writer;
    @Inject OwnerFencedJpaWriter fencedWriter;
    @Inject PendingCancellationReceiptWriter pendingWriter;
    @Inject JpaInstanceOperations operations;
    @Inject RegistryScan scan;
    @Inject TransactionManager transactions;

    @BeforeAll void installProposal() throws Exception {
        try (var c = data.getConnection(); var statement = c.createStatement()) {
            for (String schema : List.of("db/proposed/V81__pending_cancellation_receipt.sql",
                                         "db/proposed/V82__workflow_owner_lease.sql")) {
                try (var in = getClass().getClassLoader().getResourceAsStream(schema)) {
                    assertNotNull(in); statement.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
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

    @Test void resourceEntryReleasesOutputOnlyWhileOwnerRemainsCurrent() throws Exception {
        exerciseOwnedResourceEntry(0);
        exerciseOwnedResourceEntry(1);
        exerciseOwnedResourceEntry(2);
        exerciseOwnedResourceEntry(3);
        exerciseOwnedResourceEntry(4);
        exerciseOwnedResourceEntry(5);
        exerciseOwnedResourceEntry(6);
        exerciseOwnedResourceEntry(7);
    }

    // The proposed schemas are irreversible in this class's disposable database; legacy-schema cases run first.
    @Test @Order(Integer.MAX_VALUE) void auditedRootCombinesActualOwnerTaskAndEntryReceipt() throws Exception {
        try (var c = data.getConnection()) {
            c.setAutoCommit(false);
            try { ResourceTaskAllocationMigrationTest.apply(c); c.commit(); }
            catch (Exception failure) { c.rollback(); throw failure; }
        }
        exerciseOwnedResourceEntry(8);
        exerciseOwnedResourceEntry(9);
    }

    // 0: current; 1/2: takeover; 3: grant revoked; 4: policy revoked; 5/6/7: deny, PDP or PEP audit failure.
    private void exerciseOwnedResourceEntry(int scenario) throws Exception {
        String appId = "resource-entry-owner-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
                do:
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: io.openworkflow.owner.resume }
                """;
        var waiting = new CountDownLatch(1);
        var context = new AtomicReference<WorkflowContextData>();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>();
        try (var broker = broker(waiting);
             var app = WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowExecutionListener() {
                         @Override public void onWorkflowStarted(WorkflowStartedEvent event) {
                             context.set(event.workflowContext()); bindings.bind(event.workflowContext(), claim.get());
                         }
                     }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML))
                    .instance(Map.of());
            var binding = register(appId, tenant, instance);
            var leases = new JdbcWorkflowOwnerLease(data);
            claim.set(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(scenario >= 8 ? 30 : 25)).orElseThrow().claim());
            instance.start();
            assertTrue(waiting.await(10, TimeUnit.SECONDS));
            var row = store.find(tenant, binding.instanceId()).orElseThrow();
            var scope = new ResourceCallScope(tenant, row.id(), row.owner(), appId, instance.id(),
                    "agentic-test", "owner-boundary", "1.0.0", "do/0/wait", 1, "root", "agent-run", Pep.AGENT_RESEARCH_RUN,
                    "a".repeat(64), ResourceCallScope.requestFingerprint("{}"));
            var ids = UlidGenerator.shared(); var clock = Clock.systemUTC();
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var workload = new Subject(SubjectKind.WORKFLOW, "resource-owner-fixture");
            var grants = new DelegationGrants(new DelegationGrantStore(data),
                    new IdentityAudit(event -> {}, clock, ids), clock, ids, Duration.ofHours(2));
            var grant = grants.grant(tenant, row.owner(), row.owner(), workload, Set.of("oa.agent.run"),
                    now.plusSeconds(3600), null);
            String issuer = "https://resource-owner.test", token = UUID.randomUUID().toString();
            var issued = now.minusSeconds(2); var expires = now.plusSeconds(120);
            var issuedBindings = new JdbcIssuedTokenBindings(data, transactions);
            issuedBindings.bind(issuer, token,
                    new IssuedTokenBindings.Binding(grant.id(), tenant, expires));
            var credential = new ResourceCallBinding(issuer, token, grant.id(), scope, issued, expires);
            var calls = new JdbcResourceCallBindings(data, transactions);
            calls.bind(credential);
            var entry = new ResourceExecutionEntry(scope, 1);
            // Fixture verifier supplies claims; real issuer signature and provider interoperability are not tested here.
            var verified = new VerifiedToken(issuer, row.owner(), Set.of(scope.audience()),
                    Set.of(AgentResourceAdmission.SCOPE), tenant, issued, expires, token, List.of(workload.id()));
            var guard = new ResourceTokenGuard((bearer, audience) -> verified, scope.audience(),
                    new JdbcReplayCache(data), grants, issuedBindings, clock, 1);
            var admission = new AgentResourceAdmission(guard, calls, issuedBindings, scope.audience(), clock);
            var witness = admission.bind(admission.admitCall("fixture-only-bearer"), entry);
            var canonical = calls.findBinding(witness.bindingKey()).orElseThrow();
            var callbacks = new AtomicInteger();
            var auditChecks = new AtomicInteger();
            var policyAllowed = new AtomicBoolean(scenario != 5);
            var policyRequest = new AtomicReference<AuthorizationRequest>();
            var policyAudits = new AtomicInteger();
            var pep = new Pep(request -> {
                policyRequest.set(request);
                if (scenario == 6) throw new IllegalStateException("fixture policy outage");
                return new Decision("fixture", policyAllowed.get() ? DecisionOutcome.ALLOW : DecisionOutcome.DENY,
                        "fixture policy", PolicyRef.FAIL_CLOSED, clock.instant());
            }, event -> {
                policyAudits.incrementAndGet();
                if (scenario == 7) throw new IllegalStateException("fixture audit outage");
            }, Duration.ofSeconds(3), clock);
            if (scenario >= 8) {
                var agent = new Subject(SubjectKind.AGENT, "audited-root-fixture");
                var child = grants.delegate(grant.id(), workload, agent, Set.of("oa.agent.run"),
                        now.plusSeconds(600), null);
                var contract = new AgentTaskContract(ids.next(), "resource-request-sha256:" + scope.requestFingerprint(),
                        Set.of(), Set.of(), RiskTier.HIGH, new ExecutionLimits(3, Duration.ofSeconds(90), 5000),
                        row.owner(), child.id());
                var profile = new ApprovedModelProfile("root-fixture", "1", "c".repeat(64), agent,
                        "model", 1, 1000, Duration.ofSeconds(2), 256);
                var allocation = new JdbcResourceTaskAllocations(data, transactions)
                        .allocate(new ResourceTaskAllocation(scope, null, contract, profile));
                var central = new AuditAppendOnce(data);
                try (var identity = new LiveIdentityResourceFence(admission, witness, entry, canonical, workload, calls);
                     var policy = new LiveResearchPolicyFence(admission, witness, entry, pep);
                     var task = new ResourceTaskExecutionFence(data, transactions, entry, allocation, canonical, (e, t) -> {});
                     var owner = new WorkflowOwnerResourceFence(entry, context.get(), bindings,
                             InstanceRegistryFence.over(store), ownerFence);
                     var root = GovernedExecutionRoot.audited(data, transactions, 1,
                             JdbcResourceExecutionGate.IoBounds.defaults(), central::appendOnce)) {
                    assertThrows(Exception.class, () -> new ObjectMapper().writeValueAsString(root));
                    if (scenario == 9) {
                        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
                        try {
                            var pending = root.executeAsync(canonical, entry, UUID.randomUUID(), identity, policy, task, owner,
                                    ignored -> {}, running -> {
                                        entered.countDown();
                                        try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
                                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                                        return "withheld";
                                    });
                            assertTrue(entered.await(20, TimeUnit.SECONDS));
                            assertTrue(pending.cancel(false));
                            assertThrows(CancellationException.class, pending::get);
                            assertTrue(task.boundTo(entry, canonical), "cancellation must not close running task context");
                            assertDoesNotThrow(() -> identity.requireCurrent(entry));
                            release.countDown();
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            while (task.boundTo(entry, canonical) && System.nanoTime() < deadline) Thread.sleep(10);
                            assertFalse(task.boundTo(entry, canonical), "worker termination must retire task context");
                            boolean identityClosed = false;
                            while (!identityClosed && System.nanoTime() < deadline) {
                                try { identity.requireCurrent(entry); }
                                catch (IllegalStateException closed) { identityClosed = true; }
                                if (!identityClosed) Thread.sleep(10);
                            }
                            assertTrue(identityClosed, "worker termination must retire identity context");
                        } finally { release.countDown(); }
                        return;
                    }
                    var result = root.execute(canonical, entry, UUID.randomUUID(), identity, policy, task, owner,
                            ignored -> {}, running -> {
                                running.requireCurrent(entry);
                                callbacks.incrementAndGet();
                                try (var c = data.getConnection(); var q = c.prepareStatement(
                                        "select count(*) from audit.event where event_type='resource.execution.entered' and resource_id=?")) {
                                    q.setString(1, entry.fingerprint());
                                    try (var r = q.executeQuery()) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
                                } catch (Exception failure) { throw new IllegalStateException(failure); }
                                return "private";
                            });
                    assertEquals(JdbcResourceExecutionGate.Kind.CALLED, result.kind());
                    assertEquals("private", result.value());
                    var retry = root.execute(canonical, entry, UUID.randomUUID(), identity, policy, task, owner,
                            ignored -> {}, running -> { callbacks.incrementAndGet(); return "duplicate"; });
                    assertEquals(JdbcResourceExecutionGate.Kind.RETAINED, retry.kind());
                    assertEquals(1, callbacks.get());
                } finally { admission.close(); }
                return;
            }
            try (var identityCheck = new LiveIdentityResourceFence(admission, witness, entry, canonical, workload, calls);
                 var policyCheck = new LiveResearchPolicyFence(admission, witness, entry, pep);
                 var ownerCheck = new WorkflowOwnerResourceFence(entry, context.get(), bindings,
                         InstanceRegistryFence.over(store), ownerFence);
                 var gate = new JdbcResourceExecutionGate(data, transactions, 1)) {
                var builder = CompositeExecutionFence.builder();
                for (var check : CompositeExecutionFence.Check.values()) {
                    if (check == CompositeExecutionFence.Check.IDENTITY) builder.with(check, identityCheck);
                    else if (check == CompositeExecutionFence.Check.POLICY) builder.with(check, policyCheck);
                    else if (check == CompositeExecutionFence.Check.OWNER_AND_LEASE) builder.with(check, ownerCheck);
                    else if (check == CompositeExecutionFence.Check.AUDIT) builder.with(check, ignored -> {
                        if ((scenario == 2 || scenario == 3 || scenario == 4) && auditChecks.incrementAndGet() == 2) {
                            if (scenario == 2) {
                                assertTrue(leases.release(claim.get()));
                                assertTrue(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).isPresent());
                            } else if (scenario == 3) {
                                grants.revoke(grant.id(), row.owner(), "fixture revocation", null);
                            } else policyAllowed.set(false);
                        }
                    });
                    else builder.with(check, ignored -> {});
                }
                // The PDP, task/target, audit and token verifier are fixtures.
                // This fixture has no V78 task allocation, so it does not claim the complete paired path.
                var paired = JdbcResourceExecutionGate.CredentialFence.pair(canonical, builder.build());
                var wrongCredential = new ResourceCallBinding(canonical.issuer(), UUID.randomUUID().toString(),
                        canonical.issuingGrantId(), canonical.scope(), canonical.issuedAt(), canonical.expiresAt());
                assertThrows(JdbcResourceExecutionGate.RefusedException.class,
                        () -> gate.openGoverned(wrongCredential, entry, UUID.randomUUID(), paired));
                var attempt = gate.openGoverned(canonical, entry, UUID.randomUUID(), paired);
                java.util.function.Function<JdbcResourceExecutionGate.Running, String> callback = running -> {
                    callbacks.incrementAndGet(); running.requireCurrent(entry);
                    if (scenario == 1) {
                        assertTrue(leases.release(claim.get()));
                        assertTrue(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).isPresent());
                    }
                    return "private output";
                };
                if (scenario != 0) {
                    assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> attempt.executeGoverned(callback));
                    assertEquals(scenario < 5, gate.find(scope).isPresent(),
                            "a pre-entry denial must not create a fact; a post-commit refusal retains it");
                    var retry = gate.openGoverned(canonical, entry, UUID.randomUUID(), paired);
                    assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> retry.executeGoverned(callback));
                } else {
                    var result = attempt.executeGoverned(callback);
                    assertEquals(JdbcResourceExecutionGate.Kind.CALLED, result.kind());
                    assertEquals("private output", result.value());
                    var retry = gate.openGoverned(canonical, entry, UUID.randomUUID(), paired).executeGoverned(callback);
                    assertEquals(JdbcResourceExecutionGate.Kind.RETAINED, retry.kind());
                    assertNull(retry.value());
                }
                assertEquals(scenario >= 2 ? 0 : 1, callbacks.get(),
                        "callback must not start after takeover or run twice for a retained entry");
                assertNotNull(policyRequest.get());
                assertEquals(row.owner(), policyRequest.get().subject());
                assertEquals(Set.of(AgentResourceAdmission.SCOPE), policyRequest.get().context().scopes());
                assertEquals(Pep.AGENT_RESEARCH_RUN, policyRequest.get().resource());
                assertTrue(policyAudits.get() > 0, "the PEP must record its decision");
            } finally { admission.close(); }
        }
    }

    @Test void resourceOwnerCheckBindsExactEntryAndRejectsTakeover() throws Exception {
        String appId = "resource-owner-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
                do:
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: io.openworkflow.owner.resume }
                """;
        var waiting = new CountDownLatch(1);
        var context = new AtomicReference<WorkflowContextData>();
        var claim = new AtomicReference<JdbcWorkflowOwnerLease.Claim>();
        try (var broker = broker(waiting);
             var app = WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowExecutionListener() {
                         @Override public void onWorkflowStarted(WorkflowStartedEvent event) {
                             context.set(event.workflowContext()); bindings.bind(event.workflowContext(), claim.get());
                         }
                     }).build()) {
            var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML))
                    .instance(Map.of());
            var binding = register(appId, tenant, instance);
            var leases = new JdbcWorkflowOwnerLease(data);
            claim.set(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow().claim());
            instance.start();
            assertTrue(waiting.await(10, TimeUnit.SECONDS));
            var row = store.find(tenant, binding.instanceId()).orElseThrow();
            var scope = new ResourceCallScope(tenant, binding.instanceId(), row.owner(), appId, instance.id(),
                    "agentic-test", "owner-boundary", "1.0.0", "do/0/wait", 1, "root", "aud", "op",
                    "a".repeat(64), ResourceCallScope.requestFingerprint("{}"));
            var entry = new ResourceExecutionEntry(scope, 1);
            try (var fence = new WorkflowOwnerResourceFence(entry, context.get(), bindings,
                    InstanceRegistryFence.over(store), ownerFence)) {
                assertDoesNotThrow(() -> fence.requireCurrent(entry));
                var compositeBuilder = CompositeExecutionFence.builder();
                for (var check : CompositeExecutionFence.Check.values())
                    compositeBuilder.with(check, check == CompositeExecutionFence.Check.OWNER_AND_LEASE ? fence : ignored -> {});
                var composite = compositeBuilder.build();
                assertDoesNotThrow(() -> composite.requireCurrent(entry));
                var closed = new WorkflowOwnerResourceFence(entry, context.get(), bindings,
                        InstanceRegistryFence.over(store), ownerFence);
                closed.close();
                assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> closed.requireCurrent(entry));
                var enteredRegistry = new CountDownLatch(1);
                var releaseRegistry = new CountDownLatch(1);
                var blockingRegistry = new InstanceRegistryFence((t, id) -> {
                    enteredRegistry.countDown();
                    try {
                        if (!releaseRegistry.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                    }
                    return store.find(t, id);
                });
                try (var duringClose = new WorkflowOwnerResourceFence(entry, context.get(), bindings,
                        blockingRegistry, ownerFence)) {
                    var checking = CompletableFuture.runAsync(() -> duringClose.requireCurrent(entry));
                    try {
                        assertTrue(enteredRegistry.await(8, TimeUnit.SECONDS));
                        duringClose.close();
                    } finally { releaseRegistry.countDown(); }
                    assertThrows(CompletionException.class, checking::join);
                }
                try {
                    Thread.currentThread().interrupt();
                    assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> fence.requireCurrent(entry));
                    assertTrue(Thread.currentThread().isInterrupted());
                } finally { Thread.interrupted(); }
                assertThrows(JdbcResourceExecutionGate.RefusedException.class,
                        () -> fence.requireCurrent(new ResourceExecutionEntry(scope, 2)));
                assertThrows(Exception.class, () -> new ObjectMapper().writeValueAsString(fence));
                var differentApp = new ResourceCallScope(tenant, binding.instanceId(), row.owner(), "other-app",
                        instance.id(), scope.namespace(), scope.definition(), scope.version(), scope.taskPosition(),
                        scope.iteration(), scope.logicalOccurrence(), scope.audience(), scope.operation(),
                        scope.targetFingerprint(), scope.requestFingerprint());
                var wrongEntry = new ResourceExecutionEntry(differentApp, 1);
                try (var wrong = new WorkflowOwnerResourceFence(wrongEntry, context.get(), bindings,
                        InstanceRegistryFence.over(store), ownerFence)) {
                    assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> wrong.requireCurrent(wrongEntry));
                }
                assertTrue(leases.release(claim.get()));
                assertTrue(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).isPresent());
                assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> fence.requireCurrent(entry));
                assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> composite.requireCurrent(entry));
                fence.close();
                assertThrows(JdbcResourceExecutionGate.RefusedException.class, () -> fence.requireCurrent(entry));
            }
        }
    }

    @Test void failedPostAcquisitionCheckRetiresContext() throws Exception {
        String appId = "owner-denied-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
                do:
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: io.openworkflow.owner.resume }
                """;
        var waiting = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var originalBroker = broker(waiting);
             var coordinator = new WorkflowOwnerCoordinator(data, transactions, bindings, 4);
             var originalApp = WorkflowApplication.builder().withId(appId).withEventConsumer(originalBroker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(borrowed(writer, null))).build();
             var restoredApp = WorkflowApplication.builder().withId(appId)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing().build()) {
            var workflow = WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML);
            var original = originalApp.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(waiting.await(10, TimeUnit.SECONDS));
            register(appId, tenant, original);
            var starter = new RestoredWorkflowOwnerStarter(scan, store, coordinator, ownerFence,
                    Duration.ofSeconds(10), row -> { calls.incrementAndGet(); return false; });
            try (var candidate = new CancellablePersistenceReader(operations, borrowed(writer, null), 1000, starter)) {
                var restored = candidate.find(restoredApp.workflowDefinition(workflow), original.id()).orElseThrow();
                var first = restored.start();
                assertThrows(CompletionException.class, first::join);
                assertSame(first, restored.start());
                assertEquals(1, calls.get(), "a retired context must not acquire again");
                assertEquals(WorkflowStatus.PENDING, restored.status());
            }
        }
    }

    @Test void cancellationWinsWhileRestoredOwnerAcquisitionIsBlocked() throws Exception {
        String appId = "owner-cancel-race-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
                do:
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: io.openworkflow.owner.resume }
                """;
        var originalWaiting = new CountDownLatch(1); var acquired = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        var originalBroker = broker(originalWaiting); var restoredBroker = new InMemoryEvents();
        var session = new AtomicReference<WorkflowOwnerCoordinator.Session>();
        var entered = new AtomicInteger(); var worker = Executors.newSingleThreadExecutor();
        try (originalBroker; restoredBroker;
             var coordinator = new WorkflowOwnerCoordinator(data, transactions, bindings, 4);
             var originalApp = WorkflowApplication.builder().withId(appId).withEventConsumer(originalBroker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(borrowed(writer, null))).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML);
            var original = originalApp.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(originalWaiting.await(10, TimeUnit.SECONDS));
            var binding = register(appId, tenant, original);
            var starter = new RestoredWorkflowOwnerStarter(scan, store, coordinator, ownerFence,
                    Duration.ofSeconds(10), row -> true);
            CancellablePersistenceReader.OwnerStarter delayed = context -> {
                var owned = starter.acquire(context); owned.ifPresent(session::set); acquired.countDown();
                try { if (!proceed.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test did not release acquisition"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return owned;
            };
            try (var restoredApp = WorkflowApplication.builder().withId(appId).withEventConsumer(restoredBroker)
                    .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(borrowed(fencedWriter, null)))
                    .withListener(new WorkflowExecutionListener() {
                        @Override public void onTaskStarted(TaskStartedEvent event) { entered.incrementAndGet(); }
                    }).build();
                 var reader = new CancellablePersistenceReader(operations, borrowed(pendingWriter, null), 1000, delayed)) {
                var restored = reader.find(restoredApp.workflowDefinition(workflow), original.id()).orElseThrow();
                Future<CompletableFuture<WorkflowModel>> starting = worker.submit((Callable<CompletableFuture<WorkflowModel>>) restored::start);
                assertTrue(acquired.await(8, TimeUnit.SECONDS)); assertNotNull(session.get());
                var concurrentStart = restored.start();
                var current = store.find(tenant, binding.instanceId()).orElseThrow();
                store.replace(current.successor(Instant.now().truncatedTo(ChronoUnit.MICROS), InstanceStatus.CANCEL_REQUESTED,
                        current.engineInstanceId(), current.startAttempts(), null, null), current.version(), "test.cancel", current.owner());
                assertTrue(restored.cancelFuture().get(5, TimeUnit.SECONDS), "pending cancellation must not wait for acquisition");
                proceed.countDown();
                var completion = starting.get(5, TimeUnit.SECONDS);
                assertSame(concurrentStart, completion);
                assertThrows(Exception.class, () -> completion.get(5, TimeUnit.SECONDS));
                assertEquals(0, entered.get());
                assertEquals(WorkflowOwnerCoordinator.State.CLOSED, session.get().state());
                assertEquals(1, countReceipt(binding.instanceId().value()));
            }
        } finally { proceed.countDown(); worker.shutdownNow(); }
    }

    private long countReceipt(String id) throws Exception {
        try (var c = data.getConnection(); var q = c.prepareStatement("select count(*) from task.pending_cancellation_receipt where instance_id=?")) {
            q.setString(1, id); try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    @Test void competingRestoresAcquireAndRenewTheirOwnContextBeforeStarting() throws Exception {
        String appId = "owner-auto-restore-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
                do:
                  - prepare:
                      set: { prepared: true }
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: io.openworkflow.owner.resume }
                  - effect:
                      set: { effect: true }
                """;
        var initialWaiting = new CountDownLatch(1); var waitingA = new CountDownLatch(1); var waitingB = new CountDownLatch(1);
        var initialBroker = broker(initialWaiting); var brokerA = broker(waitingA); var brokerB = broker(waitingB);
        var sessionA = new AtomicReference<WorkflowOwnerCoordinator.Session>();
        var effectA = new AtomicBoolean(); var effectB = new AtomicBoolean();
        try (initialBroker; brokerA; brokerB;
             var coordinatorA = new WorkflowOwnerCoordinator(data, transactions, bindings, 8);
             var coordinatorB = new WorkflowOwnerCoordinator(data, transactions, bindings, 8);
             var originalApp = WorkflowApplication.builder().withId(appId).withEventConsumer(initialBroker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(borrowed(writer, null))).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML);
            var original = originalApp.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(initialWaiting.await(10, TimeUnit.SECONDS));
            assertEquals(1, taskCount(appId, original.id(), "do/0/prepare"));
            var binding = register(appId, tenant, original); var leases = new JdbcWorkflowOwnerLease(data);
            var starterA = new RestoredWorkflowOwnerStarter(scan, store, coordinatorA, ownerFence,
                    Duration.ofMillis(1200), row -> true); // Only this isolated test has no delegated authority/deadline gate.
            var starterB = new RestoredWorkflowOwnerStarter(scan, store, coordinatorB, ownerFence,
                    Duration.ofSeconds(10), row -> true);
            CancellablePersistenceReader.OwnerStarter captureA = context -> {
                var session = starterA.acquire(context); session.ifPresent(sessionA::set); return session;
            };
            try (var appA = restoredOwnerApp(appId, brokerA, effectA);
                 var appB = restoredOwnerApp(appId, brokerB, effectB);
                 var readerA = new CancellablePersistenceReader(operations, borrowed(writer, null), 1000, captureA);
                 var readerB = new CancellablePersistenceReader(operations, borrowed(writer, null), 1000, starterB)) {
                var a = readerA.find(appA.workflowDefinition(workflow), original.id()).orElseThrow();
                var b = readerB.find(appB.workflowDefinition(workflow), original.id()).orElseThrow();
                var firstCompletion = a.start();
                assertTrue(waitingA.await(10, TimeUnit.SECONDS)); assertNotNull(sessionA.get());
                assertThrows(Exception.class, () -> b.start().get(10, TimeUnit.SECONDS), "live owner excludes competing restore");
                assertEquals(1, waitingB.getCount());
                var firstClaim = sessionA.get().claim();
                sessionA.get().close();
                awaitLeaseExpiry(binding.instanceId().value(), Duration.ofSeconds(8));
                var secondCompletion = b.start();
                assertTrue(waitingB.await(10, TimeUnit.SECONDS));
                assertFalse(leases.release(firstClaim), "stale owner cannot release the successor");
                brokerA.publish(CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                        .withSource(URI.create("urn:owner:auto-a")).withType("io.openworkflow.owner.resume").build()).get(10, TimeUnit.SECONDS);
                assertThrows(Exception.class, () -> firstCompletion.get(10, TimeUnit.SECONDS));
                assertFalse(effectA.get());
                brokerB.publish(CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                        .withSource(URI.create("urn:owner:auto-b")).withType("io.openworkflow.owner.resume").build()).get(10, TimeUnit.SECONDS);
                secondCompletion.get(10, TimeUnit.SECONDS);
                assertTrue(effectB.get());
                assertEquals(0, taskCount(appId, original.id(), "do/0/prepare"), "successful completion cleans checkpoints");
            }
        }
    }

    private WorkflowApplication restoredOwnerApp(String appId, InMemoryEvents broker, AtomicBoolean effectStarted) {
        return WorkflowApplication.builder().withId(appId).withEventConsumer(broker)
                .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), ownerFence::requireCurrent))
                .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                .withListener(new WorkflowPersistenceListener(borrowed(fencedWriter, null)))
                .withListener(new WorkflowExecutionListener() {
                    @Override public void onTaskStarted(TaskStartedEvent event) {
                        if (event.taskContext().taskName().equals("effect")) effectStarted.set(true);
                    }
                }).build();
    }

    private void awaitLeaseExpiry(String id, Duration timeout) throws Exception {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            try (var c = data.getConnection(); var q = c.prepareStatement("select expires_at <= clock_timestamp() "
                    + "from task.workflow_owner_lease where instance_id=?")) {
                q.setString(1, id); try (var r = q.executeQuery()) { if (r.next() && r.getBoolean(1)) return; }
            }
            Thread.sleep(50);
        }
        fail("the previous owner lease did not expire");
    }

    @Test void restoredWaitDoesNotAdvanceAfterAnotherOwnerTakesOver() throws Exception { runRestoredWait(true); }
    @Test void currentOwnerCanFinishTheRestoredWait() throws Exception { runRestoredWait(false); }

    private void runRestoredWait(boolean takeover) throws Exception {
        String appId = "owner-restore-" + UUID.randomUUID(), tenant = "tenant-" + UUID.randomUUID();
        String document = """
                document: { dsl: '1.0.3', namespace: agentic-test, name: owner-boundary, version: '1.0.0' }
                do:
                  - prepare:
                      set: { prepared: true }
                  - wait:
                      listen:
                        to:
                          one:
                            with: { type: io.openworkflow.owner.resume }
                  - effect:
                      set: { effect: true }
                """;
        var firstWaiting = new CountDownLatch(1); var restoredWaiting = new CountDownLatch(1);
        var initialBroker = broker(firstWaiting); var restoredBroker = broker(restoredWaiting);
        var staleWrite = new AtomicReference<CompletableFuture<Void>>();
        var effectStarted = new AtomicBoolean();
        PersistenceInstanceWriter originalWriter = borrowed(writer, null);
        PersistenceInstanceWriter restoredWriter = borrowed(fencedWriter, staleWrite);
        try (initialBroker; restoredBroker;
             var originalApp = WorkflowApplication.builder().withId(appId).withEventConsumer(initialBroker)
                     .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                     .withListener(new WorkflowPersistenceListener(originalWriter)).build()) {
            var workflow = WorkflowReader.readWorkflowFromString(document, WorkflowFormat.YAML);
            var original = originalApp.workflowDefinition(workflow).instance(Map.of()); original.start();
            assertTrue(firstWaiting.await(10, TimeUnit.SECONDS));
            assertEquals(1, taskCount(appId, original.id(), "do/0/prepare"));
            var binding = register(appId, tenant, original); var leases = new JdbcWorkflowOwnerLease(data);
            var first = leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).orElseThrow();
            OwnerFencedTaskExecutorFactory.Check oldOwner = context -> {
                bindings.bind(context, first.claim()); // Test-only trusted composition; restored contexts emit no started event.
                ownerFence.requireCurrent(context);
            };
            try (var restoredApp = WorkflowApplication.builder().withId(appId).withEventConsumer(restoredBroker)
                    .withTaskExecutorFactory(new OwnerFencedTaskExecutorFactory(DefaultTaskExecutorFactory.get(), oldOwner))
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(restoredWriter))
                    .withListener(new WorkflowExecutionListener() {
                        @Override public void onTaskStarted(TaskStartedEvent event) {
                            if (event.taskContext().taskName().equals("effect")) effectStarted.set(true);
                        }
                    }).build()) {
                var definition = restoredApp.workflowDefinition(workflow);
                var restored = reader.find(definition, original.id()).orElseThrow();
                var completion = restored.start();
                assertTrue(restoredWaiting.await(10, TimeUnit.SECONDS), "restored wait must be active under its owner");
                if (takeover) {
                    assertTrue(leases.release(first.claim()));
                    assertTrue(leases.acquire(binding, UUID.randomUUID(), Duration.ofSeconds(15)).isPresent());
                }
                restoredBroker.publish(CloudEventBuilder.v1().withId(UUID.randomUUID().toString())
                        .withSource(URI.create("urn:owner:restore-test")).withType("io.openworkflow.owner.resume").build())
                        .get(10, TimeUnit.SECONDS);
                if (takeover) {
                    assertThrows(Exception.class, () -> completion.get(10, TimeUnit.SECONDS));
                    assertFalse(effectStarted.get(), "the stale restored context must not start the next task");
                    assertNotNull(staleWrite.get());
                    var refused = assertThrows(ExecutionException.class, () -> staleWrite.get().get(10, TimeUnit.SECONDS));
                    assertTrue(hasMessage(refused, "workflow owner lease is not current"),
                            "the owner fence must reject the checkpoint, not an unrelated persistence failure");
                    assertEquals(0, taskCount(appId, original.id(), "do/1/wait"), "stale wait checkpoint must not commit");
                    assertEquals(0, taskCount(appId, original.id(), "do/2/effect"));
                } else {
                    completion.get(10, TimeUnit.SECONDS);
                    assertTrue(effectStarted.get(), "the current owner should continue after the restored wait");
                    assertNotNull(staleWrite.get());
                    staleWrite.get().get(10, TimeUnit.SECONDS);
                    assertEquals(0, taskCount(appId, original.id(), "do/0/prepare"),
                            "completed workflow cleanup should remove its SDK checkpoints");
                }
            }
        }
    }

    private static boolean hasMessage(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (String.valueOf(cause.getMessage()).contains(text)) return true;
        return false;
    }

    private static InMemoryEvents broker(CountDownLatch waiting) {
        return new InMemoryEvents() {
            @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,
                    Consumer<CloudEvent> callback, WorkflowContext workflow, TaskContext task) {
                var registration = super.register(builder, callback, workflow, task); waiting.countDown(); return registration;
            }
        };
    }

    private static PersistenceInstanceWriter borrowed(PersistenceInstanceWriter source,
                                                       AtomicReference<CompletableFuture<Void>> taskWrite) {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    try {
                        Object result = method.invoke(source, args);
                        if (taskWrite != null && method.getName().equals("taskCompleted")) {
                            @SuppressWarnings("unchecked") var future = (CompletableFuture<Void>) result;
                            taskWrite.set(future);
                        }
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
    }

    private long taskCount(String appId, String engineId, String position) throws Exception {
        try (var c = data.getConnection(); var q = c.prepareStatement("select count(*) from public.taskinfoentity "
                + "where applicationid=? and workflowinstanceid=? and jsonpointer=?")) {
            q.setString(1, appId); q.setString(2, engineId); q.setString(3, position);
            try (var r = q.executeQuery()) { r.next(); return r.getLong(1); }
        }
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
