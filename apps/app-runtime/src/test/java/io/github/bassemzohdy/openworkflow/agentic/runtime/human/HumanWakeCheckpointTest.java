package io.github.bassemzohdy.openworkflow.agentic.runtime.human;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.*;
import io.quarkiverse.flow.persistence.jpa.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.WorkflowReader;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import io.serverlessworkflow.impl.lifecycle.*;
import io.serverlessworkflow.impl.persistence.*;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.lang.reflect.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Isolated proof of a staged human wake against the actual SDK and JPA checkpoint writer. */
@QuarkusTest
@QuarkusTestResource(value = HumanSchemaResource.class, restrictToAnnotatedClass = true)
class HumanWakeCheckpointTest {
    @Inject JpaInstanceWriter writer;
    @Inject JdbcInboxCheckpointProof proof;
    @Inject JdbcInboxDispatch dispatch;
    @Inject JdbcEventInbox inbox;
    @Inject InstanceStore registry;
    @Inject EntityManager em;
    @Inject DataSource dataSource;
    @Inject JdbcInboxTaskLease leases;
    @Inject JdbcOwnedInbox owned;
    @Inject AuditTrail audit;
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final String FILTER = "{\"with\":{\"source\":\"" + HumanDecisionWake.SOURCE
            + "\",\"type\":\"" + HumanDecisionWake.TYPE + "\"}}";
    private static final String SNAPSHOT = "{\"format\":\"oa.inbox.listen-snapshot.v1\",\"task\":{\"listen\":{\"to\":{\"one\":" + FILTER + "}}}}";
    private static final String HASH = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.event-filter.v1").text(FILTER).toBytes());
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    static final class Broker extends InMemoryEvents {
        final CompletableFuture<TypeEventRegistration> registration = new CompletableFuture<>();
        @Override public TypeEventRegistration register(TypeEventRegistrationBuilder b, Consumer<CloudEvent> callback,
                WorkflowContext workflow, TaskContext task) {
            var r = super.register(b, callback, workflow, task); registration.complete(r); return r;
        }
    }
    private PersistenceInstanceWriter borrowed() {
        return (PersistenceInstanceWriter) Proxy.newProxyInstance(PersistenceInstanceWriter.class.getClassLoader(),
                new Class<?>[]{PersistenceInstanceWriter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(writer, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    private InstanceRecord move(InstanceRecord row, InstanceStatus status, String engine) {
        return registry.replace(row.successor(now(), status, engine, row.startAttempts(), null, null), row.version(), "test.human", row.owner());
    }
    @Test void stagedWakeCommitsRealSdkCheckpointAndReceiptTogether() throws Exception { execute(false); }
    @Test void stagedWakeWithLostOwnerCannotCommitRealSdkCheckpoint() throws Exception { execute(true); }
    private void execute(boolean loseOwner) throws Exception {
        try (var gate = new PersistenceCommitGate(Duration.ofSeconds(15), Clock.systemUTC()); var broker = new Broker()) {
            var next = new CompletableFuture<Void>(); var release = new CompletableFuture<Void>();
            var guarded = new CheckpointingWriter(borrowed(), gate, (op,id) -> {});
            try (var app = WorkflowApplication.builder().withId("human-" + UUID.randomUUID()).withEventConsumer(broker)
                    .disableLifeCycleCEPublishing().disableStatusChangePublishing()
                    .withListener(new WorkflowPersistenceListener(guarded))
                    .withListener(new WorkflowExecutionCompletableListener() {
                        @Override public CompletableFuture<?> onTaskStarted(TaskStartedEvent event) {
                            if (event.taskContext().taskName().equals("afterDecision")) { next.complete(null); return release; }
                            return CompletableFuture.completedFuture(null);
                        }
                    }).build()) {
                var instance = app.workflowDefinition(WorkflowReader.readWorkflowFromClasspath("human/decision-checkpoint.yaml")).instance(Map.of());
                var start = new StartRequest("tenant", ALICE, new DefinitionRef("agentic/human-checkpoint-test", "1.0.0"), Map.of(), UUID.randomUUID().toString());
                var row = registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(), start, now()), "test.start").record();
                row = move(move(row, InstanceStatus.RUNNING, instance.id()), InstanceStatus.WAITING, instance.id());
                var completion = instance.start();
                var registration = broker.registration.get(10, TimeUnit.SECONDS);
                var task = registration.task();
                var key = TaskInfoKey.from(registration.workflow(), task);
                Ulid taskId = UlidGenerator.shared().next();
                var wait = new InboxWait(row.tenant(), row.id(), app.id(), instance.id(), "agentic", "human-checkpoint-test", "1.0.0",
                        task.position().jsonPointer(), task.iteration(), HumanDecisionWake.route(taskId), HumanDecisionWake.TYPE, HASH, Duration.ofMinutes(2));
                dispatch.register(wait);
                try (var c = dataSource.getConnection(); var q = c.prepareStatement("insert into human_proposal.task "
                        + "(tenant,task_id,instance_id,wait_key,version,status,initiator_kind,initiator_id,assignee_id,separation,risk_tier,policy_id,policy_version) "
                        + "values (?,?,?,?,0,'IN_PROGRESS','USER','bob','alice','INITIATOR_MUST_NOT_ACT','HIGH','test-policy','1')")) {
                    q.setString(1, row.tenant()); q.setString(2, taskId.value()); q.setString(3, row.id().value()); q.setString(4, wait.key()); q.executeUpdate();
                }
                var decision = new HumanDecision(UlidGenerator.shared().next(), row.tenant(), taskId, 0, ALICE, HumanDecision.Outcome.APPROVE, "private");
                var token = new VerifiedToken("issuer", ALICE, Set.of("api"), Set.of("human-task:decide"), row.tenant(), now(), now().plusSeconds(120), "id", List.of());
                var clock = Clock.systemUTC();
                var admission = new HumanDecisionAdmission(new Pep(ignored -> new Decision("test", DecisionOutcome.ALLOW, "allowed",
                        new PolicyRef("test-policy", "1"), clock.instant()), event -> {}, Duration.ofSeconds(3), clock), clock);
                new HumanDecisionCoordinator(dataSource, admission, new RegisteredHumanWaitFence(wait), clock)
                        .recordNew(CallerAuthority.ofUserToken(token, clock), decision);
                var claim = leases.acquire(wait.task(), "human-test-owner", Duration.ofSeconds(30)).orElseThrow().claim();
                try {
                    var group = new InboxListenGroup(wait.task(), InboxListenGroup.Strategy.ONE, List.of(HASH), InboxListenGroup.Stop.NONE,
                            List.of(), null, InboxListenGroup.snapshotFingerprint(SNAPSHOT));
                    assertEquals(HumanWakeStage.Result.STAGED, new HumanWakeStage(dataSource, inbox, owned, clock).stage(decision, wait, claim, group, SNAPSHOT));
                    var message = HumanDecisionWake.message(decision, wait);
                    assertEquals(Optional.of(message), inbox.boundSignal(wait));
                    var reservation = owned.reserve(claim, wait).orElseThrow();
                    InboxCheckpointMarker.install(registration.workflow(), task, reservation, claim);
                    if (loseOwner) assertTrue(leases.release(claim));
                    var event = StoredCloudEventDecoder.decode(message);
                    assertEquals(wait.engineInstanceId(), registration.workflow().instanceData().id());
                    assertTrue(registration.correlationPredicates().isEmpty());
                    assertTrue(registration.predicate().test(event, registration.workflow(), task));
                    registration.consumer().accept(event);
                    if (loseOwner) {
                        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                            while (gate.status().healthy()) Thread.sleep(10);
                        });
                        assertFalse(proof.consumed(wait, message));
                        assertFalse(QuarkusTransaction.requiringNew().call(() -> em.find(CompletedTaskEntity.class, key) != null));
                        assertThrows(TimeoutException.class, () -> next.get(150, TimeUnit.MILLISECONDS));
                        assertFalse(completion.isDone());
                        return;
                    }
                    next.get(15, TimeUnit.SECONDS);
                    assertFalse(completion.isDone());
                    assertTrue(QuarkusTransaction.requiringNew().call(() -> em.find(CompletedTaskEntity.class, key) != null));
                    assertTrue(proof.consumed(wait, message));
                    // Simulate a fresh recovery component seeing the committed checkpoint before acknowledgement.
                    var auditCalls = new java.util.concurrent.atomic.AtomicInteger();
                    var recovery = new JdbcInboxCheckpointRecovery(dataSource, recorded -> {
                        auditCalls.incrementAndGet(); audit.record(recorded);
                    }, clock);
                    assertTrue(recovery.repairBatch(row, app.id(), 16));
                    assertTrue(recovery.repairBatch(row, app.id(), 16));
                    assertEquals(1, auditCalls.get(), "repeat repair must not repeat the acknowledgement audit");
                    assertTrue(owned.reserve(claim, wait).isEmpty(), "a checkpointed wake cannot be redelivered");
                    assertEquals(JdbcInboxDispatch.Acknowledgement.ACKNOWLEDGED, owned.acknowledge(wait));
                    release.complete(null); completion.get(15, TimeUnit.SECONDS);
                    assertTrue(proof.consumed(wait, message));
                } finally { release.complete(null); leases.release(claim); }
            }
        }
    }
}
