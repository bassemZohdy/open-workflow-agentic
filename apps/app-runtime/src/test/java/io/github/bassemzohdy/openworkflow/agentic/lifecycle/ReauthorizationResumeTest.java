package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.count;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newKey;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.LifecycleSupport.newTenant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthorities;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.EngineStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.EngineStatusSync;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.HistoryEntry;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.LifecycleSettings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Reconciler;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.RegistryScan;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.RepairStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Re-authorization after a long wait (WP-102 slice d, M11): the reconciler asks the grant before it lets a restored
 * instance run again. The engine is a fake that reports a restored instance as PENDING; the registry, the grants, the
 * audit trail and the reconciler are real. The kill/restart version with Flow is {@code IdentityRecoveryIT}.
 */
@QuarkusTest
class ReauthorizationResumeTest {

    private static final Duration TINY = Duration.ofNanos(1000);
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @Inject
    JdbcInstanceStore store;

    @Inject
    RegistryScan scan;

    @Inject
    RepairStore repairs;

    @Inject
    EngineStatusSync sync;

    @Inject
    DataSource dataSource;

    @Inject
    DelegationGrants grants;

    @Inject
    InstanceAuthorities authorities;

    private final Subject alice = new Subject(SubjectKind.USER, "alice");
    private final Subject planner = new Subject(SubjectKind.AGENT, "planner");

    private Reconciler reconciler(FakeEngine engine, String tenant, ResumeGate gate) {
        LifecycleSettings settings = new LifecycleSettings(true, Duration.ofSeconds(30), Duration.ofSeconds(120), TINY, 3,
                TINY, TINY, Duration.ofHours(24), 100, Optional.empty());
        return new Reconciler(store, engine, sync, scan, repairs, settings, dataSource, Clock.systemUTC(), tenant::equals,
                gate);
    }

    private DelegationGrant grant(String tenant, Duration lifetime) {
        return grants.grant(tenant, alice, alice, planner, Set.of("cases:read"), Instant.now().plus(lifetime), null);
    }

    /** A restored instance: RUNNING in the registry, PENDING in the engine, running under {@code grantId} (or none). */
    private InstanceRecord restored(FakeEngine engine, String tenant, Ulid grantId, String name) {
        StartRequest request = new StartRequest(tenant, alice, FakeEngine.PROBE,
                Map.of("mode", "ok", "effectUrl", "http://127.0.0.1:1/unused"), newKey());
        InstanceRecord candidate = InstanceRecord.starting(UlidGenerator.shared().next(), request,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        InstanceRecord row = store.insertIfAbsent(candidate, "test.insert",
                authorities.bindAtStart(tenant, alice, grantId, "corr-" + name)).record();
        String engineId = name + "-" + RUN;
        engine.report(engineId, EngineStatus.PENDING);
        return store.replace(row.successor(Instant.now().truncatedTo(ChronoUnit.MICROS), InstanceStatus.RUNNING,
                engineId, 0, null, null), row.version(), "test.setup", InstanceService.RUNTIME_ACTOR);
    }

    private InstanceRecord reload(String tenant, InstanceRecord row) {
        return store.find(tenant, row.id()).orElseThrow();
    }

    private long historyWith(InstanceRecord row, String reason) {
        return store.history(row.id()).stream().filter(h -> reason.equals(h.reason())).count();
    }

    private long held(InstanceRecord row) {
        return count(dataSource, "select count(*) from task.repair_event where subject = ? and kind = 'resume' "
                + "and outcome = 'held'", row.id().value());
    }

    @Test
    void anInstanceWithAnActiveGrantIsResumedAsBefore() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = restored(engine, tenant, grant(tenant, Duration.ofHours(1)).id(), "active");

        reconciler(engine, tenant, authorities).reconcileOnce();

        assertEquals(1, engine.restarts.get());
        assertEquals(1L, scan.resumeEpoch(row.id().value()).orElseThrow());
        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status());
    }

    @Test
    void anInstanceWithoutAGrantIsResumedAsBefore() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = restored(engine, tenant, null, "nogrant");

        reconciler(engine, tenant, authorities).reconcileOnce();

        assertEquals(1, engine.restarts.get());
        assertEquals(1L, scan.resumeEpoch(row.id().value()).orElseThrow());
    }

    @Test
    void aRevokedGrantKeepsTheInstanceFromRunningAndSaysWhyEverywhereItIsLookedFor() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        DelegationGrant grant = grant(tenant, Duration.ofHours(1));
        InstanceRecord row = restored(engine, tenant, grant.id(), "revoked");
        grants.revoke(grant.id(), alice, "withdrawn", null);
        Reconciler reconciler = reconciler(engine, tenant, authorities);

        reconciler.reconcileOnce();
        reconciler.reconcileOnce();
        reconciler.reconcileOnce();

        // The step did not proceed: nothing was started, no epoch was spent.
        assertEquals(0, engine.restarts.get());
        assertEquals(0L, scan.resumeEpoch(row.id().value()).orElseThrow());
        // The existing WAITING semantics: the registry row waits, with a reason and a deadline.
        InstanceRecord after = reload(tenant, row);
        assertEquals(InstanceStatus.WAITING, after.status());
        assertNotNull(after.waitDeadline(), "a wait nobody renews is cancelled by the deadline repair like any wait");
        List<HistoryEntry> history = store.history(row.id());
        assertEquals(Reconciler.REASON_REAUTHORIZATION, history.get(history.size() - 1).reason());
        assertEquals(1, historyWith(row, Reconciler.REASON_REAUTHORIZATION), "written once, not once per pass");
        // The outbox row of that change carries the reason, which is what the task view shows as its last reason.
        assertEquals(1, count(dataSource, "select count(*) from task.outbox where partition_key = ? "
                + "and payload::text like '%reauthorization.required%'", row.id().value()));
        // The typed outcome and its audit event.
        InstanceAuthority authority = authorities.find(row.id()).orElseThrow();
        assertEquals(InstanceAuthority.State.REAUTHORIZATION_REQUIRED, authority.state());
        assertEquals("grant.revoked", authority.stateReason());
        assertEquals(1, count(dataSource, "select count(*) from audit.event where event_type = ? and resource_id = ?",
                InstanceAuthorities.REQUIRED_EVENT, row.id().value()));
        assertEquals(1, held(row), "the repair event is written once per audit window");
    }

    @Test
    void anExpiredGrantAfterALongWaitHoldsTheInstance() throws Exception {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        DelegationGrant grant = grant(tenant, Duration.ofSeconds(1));
        InstanceRecord row = restored(engine, tenant, grant.id(), "expired");
        Thread.sleep(1200);

        reconciler(engine, tenant, authorities).reconcileOnce();

        assertEquals(0, engine.restarts.get());
        assertEquals("grant.expired", authorities.find(row.id()).orElseThrow().stateReason());
    }

    @Test
    void afterTheUserRenewsTheInstanceIsResumedOnce() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        DelegationGrant old = grant(tenant, Duration.ofHours(1));
        InstanceRecord row = restored(engine, tenant, old.id(), "renewed");
        grants.revoke(old.id(), alice, "withdrawn", null);
        Reconciler reconciler = reconciler(engine, tenant, authorities);
        reconciler.reconcileOnce();
        assertEquals(InstanceStatus.WAITING, reload(tenant, row).status());

        DelegationGrant fresh = grant(tenant, Duration.ofHours(2));
        authorities.reauthorize(tenant, alice, row.id(), fresh.id(), "corr-renew");
        reconciler.reconcileOnce();

        assertEquals(1, engine.restarts.get());
        assertEquals(1L, scan.resumeEpoch(row.id().value()).orElseThrow());
        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status(), "the registry follows the engine again");
        assertTrue(historyWith(row, "engine.reauthorized.RUNNING") >= 1);
        assertEquals(InstanceAuthority.State.AUTHORIZED, authorities.find(row.id()).orElseThrow().state());

        reconciler.reconcileOnce();
        assertEquals(1, engine.restarts.get(), "resumed once");
    }

    @Test
    void aGateThatCannotDecideDoesNotLetTheInstanceRunAndChangesNothing() {
        String tenant = newTenant();
        FakeEngine engine = new FakeEngine();
        InstanceRecord row = restored(engine, tenant, grant(tenant, Duration.ofHours(1)).id(), "unavailable");
        ResumeGate broken = r -> {
            throw new IllegalStateException("database down");
        };
        Reconciler reconciler = reconciler(engine, tenant, broken);

        reconciler.reconcileOnce();
        reconciler.reconcileOnce();

        assertEquals(0, engine.restarts.get());
        assertEquals(0L, scan.resumeEpoch(row.id().value()).orElseThrow());
        assertEquals(InstanceStatus.RUNNING, reload(tenant, row).status(), "an outage is not a re-authorization");
        assertEquals(1, held(row));
        assertNull(reload(tenant, row).waitDeadline());

        // When the gate works again the instance runs.
        reconciler(engine, tenant, authorities).reconcileOnce();
        assertEquals(1, engine.restarts.get());
    }
}
