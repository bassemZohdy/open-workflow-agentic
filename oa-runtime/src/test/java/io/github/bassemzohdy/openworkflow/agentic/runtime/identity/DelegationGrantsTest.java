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

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.PermissionWideningException;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.GrantException.Code;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.RecordingSink;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Grants against PostgreSQL: creation, narrowing delegation, revocation, expiry, audit, and the database guards. */
class DelegationGrantsTest {

    private static final Duration MAX = Duration.ofDays(30);

    private MovableClock clock;
    private RecordingSink sink;
    private DelegationGrants grants;
    private String tenant;
    private final Subject alice = user("alice");
    private final Subject planner = agent("planner");
    private final Subject worker = agent("worker");

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-19T10:00:00Z"));
        sink = new RecordingSink();
        grants = new DelegationGrants(new DelegationGrantStore(IdentityTestSupport.dataSource()),
                IdentityTestSupport.audit(sink, clock), clock, UlidGenerator.shared(), MAX);
        tenant = newTenant();
    }

    private DelegationGrant root(Set<String> scopes, Duration lifetime) {
        return grants.grant(tenant, alice, alice, planner, scopes, clock.instant().plus(lifetime), "corr-1");
    }

    // ---- creation ------------------------------------------------------------------------------------------------------

    @Test
    void aUserGivesAGrantAndItIsAuditedWithoutAnyToken() {
        DelegationGrant grant = root(Set.of("cases:read", "cases:write"), Duration.ofHours(1));

        assertEquals(DelegationGrant.Status.ACTIVE, grant.status());
        assertEquals(List.of("planner"), grant.chain());
        assertNull(grant.parentId());
        assertTrue(grants.check(grant.id()).active());
        assertEquals(grant, grants.find(grant.id()).orElseThrow());

        AuditEvent event = sink.byAction(DelegationGrants.CREATED).get(0);
        assertEquals(grant.id().value(), event.resourceId());
        assertEquals(alice, event.actor());
        assertEquals("alice", event.attributes().get("grant.user"));
        assertEquals("planner", event.attributes().get("grant.agent"));
        assertEquals("cases:read,cases:write", event.attributes().get("grant.scopes"));
        assertEquals(tenant, event.attributes().get("oa.tenant"));
        assertEquals("corr-1", event.attributes().get("oa.correlation"));
    }

    @Test
    void onlyTheUserThemselvesGivesARootGrant() {
        GrantException e = assertThrows(GrantException.class, () -> grants.grant(tenant, alice, planner, worker,
                Set.of("cases:read"), clock.instant().plusSeconds(60), null));
        assertEquals(Code.FORBIDDEN, e.code());
        assertEquals(0, sink.events.size());
    }

    @Test
    void invalidRequestsAreRefusedAndNothingIsWritten() {
        Instant soon = clock.instant().plusSeconds(60);
        assertEquals(Code.INVALID_REQUEST, assertThrows(GrantException.class,
                () -> grants.grant(tenant, alice, alice, planner, Set.of(), soon, null)).code());
        assertEquals(Code.INVALID_REQUEST, assertThrows(GrantException.class,
                () -> grants.grant(tenant, alice, alice, planner, Set.of("Bad Scope"), soon, null)).code());
        assertEquals(Code.INVALID_REQUEST, assertThrows(GrantException.class,
                () -> grants.grant(tenant, alice, alice, planner, Set.of("a"), clock.instant(), null)).code(),
                "an expiry that is not in the future");
        assertEquals(Code.INVALID_REQUEST, assertThrows(GrantException.class,
                () -> grants.grant(tenant, alice, alice, planner, Set.of("a"), clock.instant().plus(MAX).plusSeconds(1),
                        null)).code(), "beyond the maximum lifetime");
        assertEquals(Code.INVALID_REQUEST, assertThrows(GrantException.class,
                () -> grants.grant(tenant, alice, alice, user("bob"), Set.of("a"), soon, null)).code(),
                "a grant is given to an agent, not to a user");
        assertEquals(0, count("select count(*) from identity.delegation_grant where tenant = ?", tenant));
    }

    // ---- delegation narrows --------------------------------------------------------------------------------------------

    @Test
    void anAgentDelegatesPartOfItsGrantAndTheChainIsRecorded() {
        DelegationGrant parent = root(Set.of("cases:read", "cases:write"), Duration.ofHours(2));

        DelegationGrant child = grants.delegate(parent.id(), planner, worker, Set.of("cases:read"),
                clock.instant().plus(Duration.ofHours(1)), "corr-2");

        assertEquals(List.of("planner", "worker"), child.chain());
        assertEquals(parent.id(), child.parentId());
        assertEquals(alice, child.delegator(), "the user whose authority is delegated stays the same");
        assertEquals(planner, child.createdBy());
        assertEquals(tenant, child.tenant());
        assertEquals("planner>worker", sink.byAction(DelegationGrants.CREATED).get(1).attributes().get("grant.chain"));
        assertEquals(parent.id().value(), sink.byAction(DelegationGrants.CREATED).get(1).attributes()
                .get("grant.parent"));
        assertTrue(grants.check(child.id()).active());
    }

    @Test
    void aChildCannotWidenScopesNorOutliveItsParent() {
        DelegationGrant parent = root(Set.of("cases:read"), Duration.ofHours(1));

        assertThrows(PermissionWideningException.class, () -> grants.delegate(parent.id(), planner, worker,
                Set.of("cases:read", "cases:write"), clock.instant().plusSeconds(60), null));
        assertThrows(PermissionWideningException.class, () -> grants.delegate(parent.id(), planner, worker,
                Set.of("cases:write"), clock.instant().plusSeconds(60), null));
        assertThrows(PermissionWideningException.class, () -> grants.delegate(parent.id(), planner, worker,
                Set.of("cases:read"), parent.expiresAt().plusSeconds(1), null));
        assertEquals(1, count("select count(*) from identity.delegation_grant where tenant = ?", tenant),
                "only the parent exists");
    }

    @Test
    void onlyTheParentsAgentDelegatesAndNoAgentJoinsTheChainTwice() {
        DelegationGrant parent = root(Set.of("cases:read"), Duration.ofHours(1));
        Instant soon = clock.instant().plusSeconds(60);

        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> grants.delegate(parent.id(), worker, agent("other"), Set.of("cases:read"), soon, null)).code());
        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> grants.delegate(parent.id(), alice, worker, Set.of("cases:read"), soon, null)).code(),
                "the user gives root grants; delegation is the agent's act");

        DelegationGrant child = grants.delegate(parent.id(), planner, worker, Set.of("cases:read"), soon, null);
        assertEquals(Code.CYCLE, assertThrows(GrantException.class,
                () -> grants.delegate(child.id(), worker, planner, Set.of("cases:read"), soon, null)).code(),
                "back to the planner: a cycle");
        assertEquals(Code.CYCLE, assertThrows(GrantException.class,
                () -> grants.delegate(parent.id(), planner, planner, Set.of("cases:read"), soon, null)).code());
    }

    @Test
    void theChainDepthIsLimited() {
        DelegationGrant current = root(Set.of("a"), Duration.ofHours(1));
        Subject holder = planner;
        for (int i = 1; i < DelegationGrant.MAX_DEPTH; i++) {
            Subject next = agent("agent-" + i);
            current = grants.delegate(current.id(), holder, next, Set.of("a"), clock.instant().plusSeconds(60), null);
            holder = next;
        }
        assertEquals(DelegationGrant.MAX_DEPTH, current.chain().size());
        DelegationGrant last = current;
        Subject lastHolder = holder;
        assertEquals(Code.CYCLE, assertThrows(GrantException.class, () -> grants.delegate(last.id(), lastHolder,
                agent("one-too-many"), Set.of("a"), clock.instant().plusSeconds(60), null)).code());
    }

    // ---- the database repeats the rules --------------------------------------------------------------------------------

    @Test
    void theDatabaseRefusesAWideningChildEvenWhenTheServiceIsBypassed() {
        DelegationGrant parent = root(Set.of("cases:read"), Duration.ofHours(1));
        String insert = "insert into identity.delegation_grant (id, tenant, subject_kind, subject_id, agent_kind,"
                + " agent_id, scopes, parent_grant_id, depth, chain, status, created_at, created_by_kind,"
                + " created_by_id, expires_at, version) values (?, ?, 'USER', 'alice', 'AGENT', 'sneaky', ?::text[],"
                + " ?, 1, ?::text[], 'ACTIVE', ?, 'AGENT', 'planner', ?, 0)";
        String id = UlidGenerator.shared().next().value();
        SQLException wider = tryExecute(insert, id, tenant, "{cases:read,cases:write}", parent.id().value(),
                "{planner,sneaky}", java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(parent.expiresAt()));
        assertNotNull(wider);
        assertTrue(wider.getMessage().contains("grant narrowing"), wider.getMessage());

        SQLException longer = tryExecute(insert, UlidGenerator.shared().next().value(), tenant, "{cases:read}",
                parent.id().value(), "{planner,sneaky}", java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(parent.expiresAt().plusSeconds(1)));
        assertNotNull(longer);
        assertTrue(longer.getMessage().contains("later than the parent"), longer.getMessage());

        SQLException ok = tryExecute(insert, UlidGenerator.shared().next().value(), tenant, "{cases:read}",
                parent.id().value(), "{planner,sneaky}", java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(parent.expiresAt()));
        assertNull(ok, "a narrower child passes the same insert: the test insert is valid except for the rule");
    }

    @Test
    void aGrantCanOnlyBeRevokedOrExpiredNeverChangedNeverDeletedNeverRevived() {
        DelegationGrant grant = root(Set.of("cases:read"), Duration.ofHours(1));
        String id = grant.id().value();

        assertNotNull(tryExecute("update identity.delegation_grant set scopes = '{cases:read,admin}' where id = ?", id));
        assertNotNull(tryExecute("update identity.delegation_grant set expires_at = expires_at + interval '1 day'"
                + " where id = ?", id));
        assertNotNull(tryExecute("delete from identity.delegation_grant where id = ?", id));
        assertNotNull(tryExecute("truncate identity.delegation_grant"));

        grants.revoke(grant.id(), alice, "test", null);
        SQLException revive = tryExecute("update identity.delegation_grant set status = 'ACTIVE', revoked_at = null,"
                + " revoke_reason = null where id = ?", id);
        assertNotNull(revive);
        assertEquals("REVOKED", grants.find(grant.id()).orElseThrow().status().name());
    }

    // ---- revocation ----------------------------------------------------------------------------------------------------

    @Test
    void revokingAGrantRevokesEverythingDelegatedFromIt() {
        DelegationGrant parent = root(Set.of("cases:read", "cases:write"), Duration.ofHours(2));
        DelegationGrant child = grants.delegate(parent.id(), planner, worker, Set.of("cases:read"),
                clock.instant().plusSeconds(600), null);
        DelegationGrant grandchild = grants.delegate(child.id(), worker, agent("helper"), Set.of("cases:read"),
                clock.instant().plusSeconds(300), null);

        List<DelegationGrant> revoked = grants.revoke(parent.id(), alice, "user withdrew consent", "corr-9");

        assertEquals(3, revoked.size());
        assertEquals(parent.id(), revoked.get(0).id(), "root first");
        for (DelegationGrant each : List.of(parent, child, grandchild)) {
            GrantCheck check = grants.check(each.id());
            assertFalse(check.active());
            assertEquals(GrantCheck.Reason.REVOKED, ((GrantCheck.Inactive) check).reason());
        }
        assertEquals(3, sink.byAction(DelegationGrants.REVOKED).size());
        AuditEvent event = sink.byAction(DelegationGrants.REVOKED).get(0);
        assertEquals("user withdrew consent", event.attributes().get("grant.reason"));
        assertEquals(parent.id().value(), event.attributes().get("grant.root"));
        assertEquals(List.of(), grants.revoke(parent.id(), alice, "again", null), "a second revoke changes nothing");
    }

    @Test
    void revokingAChildLeavesTheParentActive() {
        DelegationGrant parent = root(Set.of("cases:read"), Duration.ofHours(1));
        DelegationGrant child = grants.delegate(parent.id(), planner, worker, Set.of("cases:read"),
                clock.instant().plusSeconds(60), null);

        grants.revoke(child.id(), planner, "done", null);

        assertTrue(grants.check(parent.id()).active());
        assertFalse(grants.check(child.id()).active());
    }

    @Test
    void onlyThePartiesToAGrantCanRevokeItAndAnOperatorHasItsOwnEntry() {
        DelegationGrant grant = root(Set.of("cases:read"), Duration.ofHours(1));

        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> grants.revoke(grant.id(), user("mallory"), "x", null)).code());
        assertEquals(Code.FORBIDDEN, assertThrows(GrantException.class,
                () -> grants.revoke(grant.id(), agent("other"), "x", null)).code());
        assertTrue(grants.check(grant.id()).active());

        grants.revokeByOperator(grant.id(), user("ops-lead"), "incident 42", null);
        assertFalse(grants.check(grant.id()).active());
        assertEquals(Code.NOT_FOUND, assertThrows(GrantException.class, () -> grants.revoke(
                UlidGenerator.shared().next(), alice, "x", null)).code());
    }

    // ---- expiry --------------------------------------------------------------------------------------------------------

    @Test
    void aGrantIsUnusableFromItsExpiryEvenBeforeTheSweepMarksIt() {
        DelegationGrant grant = root(Set.of("cases:read"), Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(10).minusMillis(1));
        assertTrue(grants.check(grant.id()).active());

        clock.advance(Duration.ofMillis(1));
        GrantCheck check = grants.check(grant.id());
        assertEquals(GrantCheck.Reason.EXPIRED, ((GrantCheck.Inactive) check).reason());
        assertEquals("ACTIVE", grants.find(grant.id()).orElseThrow().status().name(), "not yet marked: still safe");
        assertEquals(List.of(), grants.activeFor(tenant, "alice", "planner"));
    }

    @Test
    void theSweepMarksExpiredGrantsAndAuditsThemOnce() {
        DelegationGrant soon = root(Set.of("cases:read"), Duration.ofMinutes(10));
        DelegationGrant later = grants.grant(tenant, alice, alice, agent("other"), Set.of("cases:read"),
                clock.instant().plus(Duration.ofHours(5)), null);
        clock.advance(Duration.ofMinutes(11));

        assertTrue(grants.expireDue() >= 1);

        assertEquals(DelegationGrant.Status.EXPIRED, grants.find(soon.id()).orElseThrow().status());
        assertEquals(DelegationGrant.Status.ACTIVE, grants.find(later.id()).orElseThrow().status());
        assertEquals(1, sink.byAction(DelegationGrants.EXPIRED).stream()
                .filter(e -> e.resourceId().equals(soon.id().value())).count());
        grants.expireDue();
        assertEquals(1, sink.byAction(DelegationGrants.EXPIRED).stream()
                .filter(e -> e.resourceId().equals(soon.id().value())).count(), "audited once");
        assertEquals(GrantCheck.Reason.EXPIRED, ((GrantCheck.Inactive) grants.check(soon.id())).reason());
    }

    @Test
    void anExpiredParentEndsItsChildrenAndCannotDelegate() {
        DelegationGrant parent = root(Set.of("cases:read"), Duration.ofMinutes(10));
        DelegationGrant child = grants.delegate(parent.id(), planner, worker, Set.of("cases:read"),
                clock.instant().plus(Duration.ofMinutes(5)), null);
        clock.advance(Duration.ofMinutes(10));

        assertFalse(grants.check(child.id()).active());
        assertEquals(Code.PARENT_INACTIVE, assertThrows(GrantException.class, () -> grants.delegate(parent.id(),
                planner, agent("late"), Set.of("cases:read"), clock.instant().plusSeconds(60), null)).code());
    }

    @Test
    void aChildIsInactiveWhenAnAncestorIsEvenIfItsOwnRowStillSaysActive() {
        DelegationGrant parent = root(Set.of("cases:read"), Duration.ofHours(1));
        DelegationGrant child = grants.delegate(parent.id(), planner, worker, Set.of("cases:read"),
                clock.instant().plusSeconds(600), null);
        // A revocation that did not cascade (a direct write): the check still refuses the child.
        assertNull(tryExecute("update identity.delegation_grant set status = 'REVOKED', revoked_at = now(),"
                + " revoke_reason = 'direct', version = version + 1 where id = ?", parent.id().value()));

        GrantCheck check = grants.check(child.id());

        assertEquals(DelegationGrant.Status.ACTIVE, grants.find(child.id()).orElseThrow().status());
        assertEquals(GrantCheck.Reason.ANCESTOR_INACTIVE, ((GrantCheck.Inactive) check).reason());
        assertEquals(List.of(), grants.activeFor(tenant, "alice", "worker"));
    }

    @Test
    void anUnknownGrantIsInactiveNotActive() {
        GrantCheck check = grants.check(UlidGenerator.shared().next());
        assertEquals(GrantCheck.Reason.UNKNOWN, ((GrantCheck.Inactive) check).reason());
    }

    // ---- the audit trail is part of the decision -----------------------------------------------------------------------

    @Test
    void ifTheCreationCannotBeAuditedTheGrantIsRevokedAndTheCallFails() {
        sink.failing = true;

        GrantException e = assertThrows(GrantException.class, () -> root(Set.of("cases:read"), Duration.ofHours(1)));

        assertEquals(Code.UNAVAILABLE, e.code());
        assertEquals(0, count("select count(*) from identity.delegation_grant where tenant = ? and status = 'ACTIVE'",
                tenant), "no active grant without an audit event");
        assertEquals(1, count("select count(*) from identity.delegation_grant where tenant = ?"
                + " and status = 'REVOKED' and revoke_reason = 'audit-unavailable'", tenant));
    }

    @Test
    void aRevocationStandsEvenWhenItsAuditEventCannotBeWritten() {
        DelegationGrant grant = root(Set.of("cases:read"), Duration.ofHours(1));
        sink.failing = true;

        grants.revoke(grant.id(), alice, "containment", null);

        assertFalse(grants.check(grant.id()).active());
    }

    @Test
    void theRealAuditTrailReceivesTheEventsAsAHashChain() {
        DelegationGrants real = new DelegationGrants(new DelegationGrantStore(IdentityTestSupport.dataSource()),
                IdentityTestSupport.audit(IdentityTestSupport.trail(), clock), clock, UlidGenerator.shared(), MAX);
        DelegationGrant grant = real.grant(tenant, alice, alice, planner, Set.of("cases:read"),
                clock.instant().plusSeconds(600), "corr-real");
        real.revoke(grant.id(), alice, "done", "corr-real");

        assertEquals(2, count("select count(*) from audit.event where resource_id = ?", grant.id().value()));
        assertEquals(1, count("select count(*) from audit.event where resource_id = ? and event_type = ?",
                grant.id().value(), DelegationGrants.CREATED));
    }

    @Test
    void activeGrantsOfAUserForAnAgentAreListedNewestFirst() {
        DelegationGrant first = root(Set.of("cases:read"), Duration.ofHours(1));
        DelegationGrant second = root(Set.of("cases:write"), Duration.ofHours(1));
        grants.revoke(first.id(), alice, "x", null);

        List<DelegationGrant> active = grants.activeFor(tenant, "alice", "planner");

        assertEquals(List.of(second.id()), active.stream().map(DelegationGrant::id).toList());
        assertEquals(List.of(), grants.activeFor(newTenant(), "alice", "planner"), "another tenant sees nothing");
    }
}
