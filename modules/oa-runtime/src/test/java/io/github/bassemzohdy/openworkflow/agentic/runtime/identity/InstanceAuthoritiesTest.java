package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.agent;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.count;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.newTenant;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.tryExecute;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GrantException.Code;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.RecordingSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceService;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Outcome;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The identity of instances against PostgreSQL: binding at start (atomic with the instance), the re-check before a
 * restored instance continues, re-authorization, the database guards, and carrying the identity into other threads.
 */
class InstanceAuthoritiesTest {

    private static final DefinitionRef PROBE = new DefinitionRef("agentic/lifecycle-probe", "0.1.0");

    private MovableClock clock;
    private RecordingSink sink;
    private DelegationGrants grants;
    private InstanceAuthorityStore authorityStore;
    private InstanceAuthorities authorities;
    private JdbcInstanceStore instances;
    private String tenant;
    private final Subject alice = user("alice");
    private final Subject bob = user("bob");
    private final Subject planner = agent("planner");
    private final Subject worker = agent("worker");

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-19T10:00:00Z"));
        sink = new RecordingSink();
        IdentityAudit audit = IdentityTestSupport.audit(sink, clock);
        grants = new DelegationGrants(new DelegationGrantStore(IdentityTestSupport.dataSource()), audit, clock,
                UlidGenerator.shared(), Duration.ofDays(30));
        authorityStore = new InstanceAuthorityStore(IdentityTestSupport.dataSource());
        authorities = new InstanceAuthorities(authorityStore, grants, audit, clock);
        instances = new JdbcInstanceStore(IdentityTestSupport.dataSource());
        tenant = newTenant();
    }

    private DelegationGrant grantFor(Subject user, Subject agent, Duration lifetime) {
        return grants.grant(tenant, user, user, agent, Set.of("cases:read"), clock.instant().plus(lifetime), null);
    }

    private InstanceRecord start(Subject user, Ulid grantId) {
        StartRequest request = new StartRequest(tenant, user, PROBE, Map.of(), "key-" + UUID.randomUUID());
        InstanceRecord candidate = InstanceRecord.starting(UlidGenerator.shared().next(), request,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        return instances.insertIfAbsent(candidate, "test.insert",
                authorities.bindAtStart(tenant, user, grantId, "corr-" + UUID.randomUUID().toString().substring(0, 8)))
                .record();
    }

    private InstanceRecord withEngineId(InstanceRecord row, String engineId) {
        return instances.replace(row.successor(clock.instant().truncatedTo(ChronoUnit.MICROS), row.status(), engineId,
                row.startAttempts(), row.lastError(), null), row.version(), "test.engine", InstanceService.RUNTIME_ACTOR);
    }

    // ---- binding at start --------------------------------------------------------------------------------------------

    @Test
    void anInstanceStartedUnderAGrantStoresOnlyIdentifiers() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));

        InstanceRecord row = start(alice, grant.id());

        InstanceAuthority authority = authorities.find(row.id()).orElseThrow();
        assertEquals(alice, authority.subject());
        assertEquals(grant.id(), authority.grantId());
        assertNotNull(authority.correlationId());
        assertEquals(InstanceAuthority.State.AUTHORIZED, authority.state());
        assertEquals(tenant, authority.tenant());
        assertTrue(authority.delegated());
    }

    @Test
    void anInstanceWithoutAGrantCarriesNoDelegatedAuthorityAndAlwaysProceeds() {
        InstanceRecord row = start(alice, null);

        assertFalse(authorities.find(row.id()).orElseThrow().delegated());
        assertEquals(Outcome.PROCEED, authorities.check(row).outcome());
    }

    @Test
    void theIdentityRowAndTheInstanceRowAreOneTransaction() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        StartRequest request = new StartRequest(tenant, alice, PROBE, Map.of(), "key-atomic");
        InstanceRecord candidate = InstanceRecord.starting(UlidGenerator.shared().next(), request,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        var hook = authorities.bindAtStart(tenant, alice, grant.id(), null);

        assertThrows(IllegalStateException.class, () -> instances.insertIfAbsent(candidate, "test.insert",
                (connection, id) -> {
                    hook.afterInsert(connection, id);
                    throw new IllegalStateException("boom after the authority row");
                }));

        assertEquals(0, count("select count(*) from task.instance where id = ?", candidate.id().value()));
        assertEquals(0, count("select count(*) from identity.instance_authority where instance_id = ?",
                candidate.id().value()), "no authority row without its instance");
    }

    @Test
    void aRepeatedKeyStartsNothingAndWritesNoSecondIdentity() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        StartRequest request = new StartRequest(tenant, alice, PROBE, Map.of(), "key-repeat");
        InstanceRecord first = InstanceRecord.starting(UlidGenerator.shared().next(), request,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        InstanceRecord second = InstanceRecord.starting(UlidGenerator.shared().next(), request,
                clock.instant().truncatedTo(ChronoUnit.MICROS));

        assertTrue(instances.insertIfAbsent(first, "t", authorities.bindAtStart(tenant, alice, grant.id(), null)).created());
        var again = instances.insertIfAbsent(second, "t", authorities.bindAtStart(tenant, alice, grant.id(), null));

        assertFalse(again.created());
        assertEquals(first.id(), again.record().id());
        assertEquals(0, count("select count(*) from identity.instance_authority where instance_id = ?",
                second.id().value()));
    }

    @Test
    void aGrantThatIsNotTheCallersIsRefusedAsUnknownAndNothingIsStarted() {
        DelegationGrant bobs = grantFor(bob, planner, Duration.ofHours(1));
        DelegationGrant ofAnotherTenant = grants.grant(newTenant(), alice, alice, planner, Set.of("cases:read"),
                clock.instant().plus(Duration.ofHours(1)), null);

        assertEquals(Code.NOT_FOUND, assertThrows(GrantException.class,
                () -> authorities.bindAtStart(tenant, alice, bobs.id(), null)).code(), "another user's grant");
        assertEquals(Code.NOT_FOUND, assertThrows(GrantException.class,
                () -> authorities.bindAtStart(tenant, alice, ofAnotherTenant.id(), null)).code(), "another tenant's");
        assertEquals(Code.NOT_FOUND, assertThrows(GrantException.class,
                () -> authorities.bindAtStart(tenant, alice, UlidGenerator.shared().next(), null)).code(), "unknown");
    }

    @Test
    void aRevokedOrExpiredGrantCannotStartAnInstance() {
        DelegationGrant revoked = grantFor(alice, planner, Duration.ofHours(1));
        grants.revoke(revoked.id(), alice, "no", null);
        DelegationGrant shortLived = grantFor(alice, planner, Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));

        assertEquals(Code.INACTIVE, assertThrows(GrantException.class,
                () -> authorities.bindAtStart(tenant, alice, revoked.id(), null)).code());
        assertEquals(Code.INACTIVE, assertThrows(GrantException.class,
                () -> authorities.bindAtStart(tenant, alice, shortLived.id(), null)).code());
    }

    @Test
    void anAgentOrAChildGrantCannotBeTheGrantOfAnInstance() {
        DelegationGrant root = grantFor(alice, planner, Duration.ofHours(1));
        DelegationGrant child = grants.delegate(root.id(), planner, worker, Set.of("cases:read"),
                clock.instant().plus(Duration.ofMinutes(30)), null);

        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> authorities.bindAtStart(tenant, planner, root.id(), null)).code(), "a non-user caller");
        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> authorities.bindAtStart(tenant, alice, child.id(), null)).code(), "a delegated child grant");
    }

    // ---- the re-check before a restored instance continues --------------------------------------------------------------

    @Test
    void anActiveGrantLetsTheInstanceProceedWithoutAuditOrChange() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = start(alice, grant.id());

        ResumeGate.Decision decision = authorities.check(row);

        assertEquals(Outcome.PROCEED, decision.outcome());
        assertEquals(InstanceAuthority.State.AUTHORIZED, authorities.find(row.id()).orElseThrow().state());
        assertTrue(sink.byAction(InstanceAuthorities.REQUIRED_EVENT).isEmpty());
    }

    @Test
    void aRevokedGrantHoldsTheInstanceMarksItAndAuditsOnce() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = start(alice, grant.id());
        grants.revoke(grant.id(), alice, "withdrawn", null);

        ResumeGate.Decision first = authorities.check(row);
        ResumeGate.Decision second = authorities.check(row);

        assertEquals(Outcome.REAUTHORIZATION_REQUIRED, first.outcome());
        assertEquals("grant.revoked", first.reason());
        assertEquals(Outcome.REAUTHORIZATION_REQUIRED, second.outcome());
        InstanceAuthority authority = authorities.find(row.id()).orElseThrow();
        assertEquals(InstanceAuthority.State.REAUTHORIZATION_REQUIRED, authority.state());
        assertEquals("grant.revoked", authority.stateReason());
        assertEquals(1, sink.byAction(InstanceAuthorities.REQUIRED_EVENT).size(), "one event, not one per check");
        var event = sink.byAction(InstanceAuthorities.REQUIRED_EVENT).get(0);
        assertEquals(row.id().value(), event.resourceId());
        assertEquals(grant.id().value(), event.attributes().get("grantId"));
        assertEquals(tenant, event.attributes().get("oa.tenant"));
        assertEquals(authority.correlationId(), event.attributes().get("oa.correlation"));
    }

    @Test
    void anExpiredGrantHoldsTheInstanceWithTheExpiredReason() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = start(alice, grant.id());
        clock.advance(Duration.ofHours(2));   // a long wait

        ResumeGate.Decision decision = authorities.check(row);

        assertEquals(Outcome.REAUTHORIZATION_REQUIRED, decision.outcome());
        assertEquals("grant.expired", decision.reason());
    }

    @Test
    void aRevokedParentHoldsAnInstanceThatRunsUnderTheChild() {
        // An instance runs under a user's root grant; if that grant is revoked, so is everything below it.
        DelegationGrant root = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = start(alice, root.id());
        grants.revoke(root.id(), alice, "withdrawn", null);

        assertEquals(Outcome.REAUTHORIZATION_REQUIRED, authorities.check(row).outcome());
    }

    @Test
    void ifTheEventCannotBeWrittenNothingIsMarkedAndTheInstanceStillDoesNotProceed() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = start(alice, grant.id());
        grants.revoke(grant.id(), alice, "withdrawn", null);
        sink.failing = true;

        assertThrows(RuntimeException.class, () -> authorities.check(row), "the gate throws; the reconciler holds");
        assertEquals(InstanceAuthority.State.AUTHORIZED, authorities.find(row.id()).orElseThrow().state(),
                "no mark without a record");
        assertEquals(Outcome.UNAVAILABLE, authorities.recheck(row.id()).outcome());

        sink.failing = false;
        assertEquals(Outcome.REAUTHORIZATION_REQUIRED, authorities.check(row).outcome());
        assertEquals(InstanceAuthority.State.REAUTHORIZATION_REQUIRED, authorities.find(row.id()).orElseThrow().state());
    }

    @Test
    void aDatabaseFailureIsUnavailableNeverProceed() {
        InstanceAuthorityStore broken = new InstanceAuthorityStore(new javax.sql.DataSource() {
            @Override
            public java.sql.Connection getConnection() throws SQLException {
                throw new SQLException("down");
            }

            @Override
            public java.sql.Connection getConnection(String u, String p) throws SQLException {
                throw new SQLException("down");
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return null;
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        });
        InstanceAuthorities down = new InstanceAuthorities(broken, grants, IdentityTestSupport.audit(sink, clock), clock);

        assertEquals(Outcome.UNAVAILABLE, down.recheck(UlidGenerator.shared().next()).outcome());
    }

    // ---- re-authorization ----------------------------------------------------------------------------------------------

    private InstanceRecord heldInstance(DelegationGrant grant) {
        InstanceRecord row = start(alice, grant.id());
        grants.revoke(grant.id(), alice, "withdrawn", null);
        assertEquals(Outcome.REAUTHORIZATION_REQUIRED, authorities.check(row).outcome());
        return row;
    }

    @Test
    void theUserRenewsWithANewGrantToTheSameAgentAndTheInstanceMayProceed() {
        DelegationGrant old = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = heldInstance(old);
        DelegationGrant fresh = grantFor(alice, planner, Duration.ofHours(2));

        InstanceAuthority renewed = authorities.reauthorize(tenant, alice, row.id(), fresh.id(), "corr-renew");

        assertEquals(fresh.id(), renewed.grantId());
        assertEquals(InstanceAuthority.State.AUTHORIZED, renewed.state());
        assertNull(renewed.stateReason());
        assertEquals(Outcome.PROCEED, authorities.check(row).outcome());
        var event = sink.byAction(InstanceAuthorities.RENEWED_EVENT).get(0);
        assertEquals(old.id().value(), event.attributes().get("previousGrantId"));
        assertEquals(fresh.id().value(), event.attributes().get("grantId"));
        assertEquals(alice, event.actor());
    }

    @Test
    void onlyTheUserWhoStartedTheInstanceRenewsItAndTheNewGrantMustBeTheirOwnToTheSameAgent() {
        DelegationGrant old = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = heldInstance(old);
        DelegationGrant bobsGrant = grantFor(bob, planner, Duration.ofHours(2));
        DelegationGrant otherAgent = grantFor(alice, worker, Duration.ofHours(2));
        DelegationGrant stillOld = old;
        DelegationGrant fresh = grantFor(alice, planner, Duration.ofHours(2));

        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> authorities.reauthorize(tenant, bob, row.id(), bobsGrant.id(), null)).code(), "not the starter");
        assertEquals(Code.NOT_FOUND, assertThrows(GrantException.class,
                () -> authorities.reauthorize(tenant, alice, row.id(), bobsGrant.id(), null)).code(), "bob's grant");
        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> authorities.reauthorize(tenant, alice, row.id(), otherAgent.id(), null)).code(), "another agent");
        assertEquals(Code.INACTIVE, assertThrows(GrantException.class,
                () -> authorities.reauthorize(tenant, alice, row.id(), stillOld.id(), null)).code(), "the revoked grant");
        assertEquals(Code.NOT_FOUND, assertThrows(GrantException.class,
                () -> authorities.reauthorize(newTenant(), alice, row.id(), fresh.id(), null)).code(), "other tenant");
        assertEquals(InstanceAuthority.State.REAUTHORIZATION_REQUIRED, authorities.find(row.id()).orElseThrow().state());
        assertTrue(sink.byAction(InstanceAuthorities.RENEWED_EVENT).isEmpty());
    }

    @Test
    void aRenewalOfAnInstanceThatIsNotWaitingIsRefusedAndTwoRenewalsDoNotBothWin() {
        DelegationGrant old = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord fine = start(alice, old.id());
        DelegationGrant fresh = grantFor(alice, planner, Duration.ofHours(2));

        assertEquals(Code.INVALID_REQUEST, assertThrows(GrantException.class,
                () -> authorities.reauthorize(tenant, alice, fine.id(), fresh.id(), null)).code());

        DelegationGrant second = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord held = heldInstance(second);
        authorities.reauthorize(tenant, alice, held.id(), fresh.id(), null);
        DelegationGrant third = grantFor(alice, planner, Duration.ofHours(3));
        assertEquals(Code.INVALID_REQUEST, assertThrows(GrantException.class,
                () -> authorities.reauthorize(tenant, alice, held.id(), third.id(), null)).code(),
                "once renewed it no longer waits");
    }

    // ---- the database guards -------------------------------------------------------------------------------------------

    @Test
    void theDatabaseRefusesATokenShapedValueEvenIfCodeTriedToStoreOne() {
        String id = UlidGenerator.shared().next().value();
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnbmF0dXJl";

        SQLException tokenAsCorrelation = tryExecute("""
                insert into identity.instance_authority (instance_id, tenant, subject_kind, subject_id, correlation_id,
                    state_since, created_at, updated_at) values (?, 't', 'USER', 'alice', ?, now(), now(), now())
                """, id, jwt);
        SQLException tokenAsSubject = tryExecute("""
                insert into identity.instance_authority (instance_id, tenant, subject_kind, subject_id,
                    state_since, created_at, updated_at) values (?, 't', 'USER', ?, now(), now(), now())
                """, id, jwt);
        SQLException longValue = tryExecute("""
                insert into identity.instance_authority (instance_id, tenant, subject_kind, subject_id, correlation_id,
                    state_since, created_at, updated_at) values (?, 't', 'USER', 'alice', ?, now(), now(), now())
                """, id, "x".repeat(129));

        assertNotNull(tokenAsCorrelation);
        assertNotNull(tokenAsSubject);
        assertNotNull(longValue);
    }

    @Test
    void theSubjectAndCorrelationOfAnInstanceNeverChangeAndRowsAreNotDeleted() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = start(alice, grant.id());

        assertNotNull(tryExecute("update identity.instance_authority set subject_id = 'mallory' where instance_id = ?",
                row.id().value()));
        assertNotNull(tryExecute("update identity.instance_authority set correlation_id = 'other' where instance_id = ?",
                row.id().value()));
        assertNotNull(tryExecute("delete from identity.instance_authority where instance_id = ?", row.id().value()));
        InstanceRecord none = start(alice, null);
        assertNotNull(tryExecute("update identity.instance_authority set grant_id = ? where instance_id = ?",
                grant.id().value(), none.id().value()), "an instance without a grant cannot gain one");
    }

    // ---- carrying the identity into other threads ----------------------------------------------------------------------

    @Test
    void aTaskThatCallsBackWithTheEngineIdGetsItsIdentityBackFromTheRegistry() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = withEngineId(start(alice, grant.id()), "engine-" + UUID.randomUUID());

        assertTrue(AuthorityScope.current().isEmpty());
        try (AuthorityScope ignored = authorities.restore(row.engineInstanceId()).orElseThrow()) {
            InstanceAuthority current = AuthorityScope.current().orElseThrow();
            assertEquals(alice, current.subject());
            assertEquals(grant.id(), current.grantId());
            assertEquals(row.id(), current.instanceId());
            assertEquals("alice", org.jboss.logging.MDC.get(AuthorityScope.MDC_SUBJECT));
            assertEquals(grant.id().value(), org.jboss.logging.MDC.get(AuthorityScope.MDC_GRANT));
        }
        assertTrue(AuthorityScope.current().isEmpty(), "the scope ends with the work");
        assertNull(org.jboss.logging.MDC.get(AuthorityScope.MDC_SUBJECT));
        assertEquals(Optional.empty(), authorities.restore("unknown-engine-id"));
        assertEquals(Optional.empty(), authorities.restore(null));
    }

    @Test
    void wrappedWorkRunsUnderTheIdentityOfItsSubmitterAndPooledThreadsDoNotLeakIt() throws Exception {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceRecord row = start(alice, grant.id());
        InstanceAuthority authority = authorities.find(row.id()).orElseThrow();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            CompletableFuture<Optional<InstanceAuthority>> inside = new CompletableFuture<>();
            try (AuthorityScope ignored = AuthorityScope.enter(authority)) {
                AuthorityScope.wrapExecutor(pool).execute(() -> inside.complete(AuthorityScope.current()));
            }
            assertEquals(Optional.of(authority), inside.get());

            Optional<InstanceAuthority> later = pool.submit(AuthorityScope::current).get();
            assertTrue(later.isEmpty(), "the next task on the same thread has no identity");

            Optional<InstanceAuthority> unwrapped;
            try (AuthorityScope ignored = AuthorityScope.enter(authority)) {
                unwrapped = pool.submit(AuthorityScope::current).get();
            }
            assertTrue(unwrapped.isEmpty(), "work that was not wrapped does not inherit an identity by accident");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nestedScopesRestoreTheOuterOne() {
        DelegationGrant grant = grantFor(alice, planner, Duration.ofHours(1));
        InstanceAuthority outer = authorities.find(start(alice, grant.id()).id()).orElseThrow();
        InstanceAuthority inner = authorities.find(start(alice, null).id()).orElseThrow();

        try (AuthorityScope o = AuthorityScope.enter(outer)) {
            try (AuthorityScope i = AuthorityScope.enter(inner)) {
                assertEquals(inner, AuthorityScope.current().orElseThrow());
                assertNull(org.jboss.logging.MDC.get(AuthorityScope.MDC_GRANT), "no grant for the inner instance");
            }
            assertEquals(outer, AuthorityScope.current().orElseThrow());
            assertEquals(grant.id().value(), org.jboss.logging.MDC.get(AuthorityScope.MDC_GRANT));
        }
    }
}
