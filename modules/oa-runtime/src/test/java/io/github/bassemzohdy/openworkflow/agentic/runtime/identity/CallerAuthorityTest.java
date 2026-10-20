package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP-108: the transient authority handle. A user or operator token is judged again at the moment of use (signed expiry,
 * scopes); a delegated token also against its exact issuing grant chain, with no second replay admission; development
 * headers and hand-built callers are never current. Every refusal is a thrown {@link AuthorityLostException}, which a
 * caller turns into zero mutation.
 */
class CallerAuthorityTest {

    private static final String ISSUER = "https://idp.test";
    private static final String AUDIENCE = "oa-agent-run";
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "research-workflow");
    private static final Set<String> RECOVER = Set.of("oa.recover");

    private MovableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
    }

    private VerifiedToken userToken(Set<String> scopes, Duration life) {
        return new VerifiedToken(ISSUER, ALICE, Set.of("oa-api"), scopes, "tenant-a", clock.instant(),
                clock.instant().plus(life), UUID.randomUUID().toString(), List.of());
    }

    private static Reason refusal(CallerAuthority authority, Set<String> scopes) {
        return assertThrows(AuthorityLostException.class, () -> authority.requireCurrent(scopes)).reason();
    }

    @Test
    void aUserOrOperatorTokenIsCurrentUntilItsSignedExpiry() {
        CallerAuthority authority = CallerAuthority.ofUserToken(userToken(RECOVER, Duration.ofMinutes(5)), clock);
        authority.requireCurrent(RECOVER);
        assertEquals("tenant-a", authority.caller().tenant());
        assertTrue(authority.caller().verified());
        assertFalse(authority.delegated());
        clock.advance(Duration.ofMinutes(5)); // exp must be strictly in the future
        assertEquals(Reason.EXPIRED, refusal(authority, RECOVER));
    }

    @Test
    void aTokenWithoutTheRequiredScopeIsNeverCurrent() {
        CallerAuthority authority = CallerAuthority.ofUserToken(userToken(Set.of("oa.inspect"), Duration.ofMinutes(5)),
                clock);
        assertEquals(Reason.SCOPE, refusal(authority, RECOVER));
    }

    @Test
    void developmentHeadersAndHandBuiltCallersAreNeverCurrent() {
        var seam = new CallerContextResolver((bearer, audience) -> { throw new AssertionError("no token"); }, "oa-api",
                true, Set.of("test"), Set.of("test"), clock);
        CallerAuthority fromHeaders = seam.resolveAuthority(headers("alice", "oa.recover"));
        assertEquals(CallerContext.Provenance.DEV_HEADERS, fromHeaders.caller().provenance());
        assertEquals(Reason.NOT_VERIFIED, refusal(fromHeaders, RECOVER));
        assertEquals(Reason.NOT_VERIFIED, refusal(CallerAuthority.unverified(
                new CallerContext("tenant-a", ALICE, RECOVER), clock), RECOVER));
        assertEquals(Reason.NOT_VERIFIED, refusal(CallerAuthority.unverified(
                new CallerContext("tenant-a", ALICE, RECOVER, CallerContext.Provenance.VERIFIED_TOKEN), clock), Set.of()));
    }

    @Test
    void aResolvedBearerKeepsItsSignedExpiryForLaterUse() {
        VerifiedToken token = userToken(RECOVER, Duration.ofMinutes(5));
        var resolver = new CallerContextResolver((bearer, audience) -> token, "oa-api", false, Set.of("test"),
                Set.of("test"), clock);
        CallerAuthority authority = resolver.resolveAuthority(bearerHeaders());
        authority.requireCurrent(RECOVER);
        clock.advance(Duration.ofMinutes(6)); // a slow policy/audit/store call crossed the expiry
        assertEquals(Reason.EXPIRED, refusal(authority, RECOVER));
    }

    @Test
    void anOwnTokenCannotBeBuiltAsDelegatedOrTheReverse() {
        VerifiedToken delegated = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of("oa.recover"), "tenant-a",
                clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), "j", List.of(WORKFLOW.id()));
        assertThrows(IllegalArgumentException.class, () -> CallerAuthority.ofUserToken(delegated, clock));
        assertThrows(IllegalArgumentException.class, () -> CallerAuthority.ofDelegatedToken(
                userToken(RECOVER, Duration.ofMinutes(5)), clock, (t, s) -> { }));
    }

    // ---- delegated: the exact grant chain, no second replay admission ------------------------------------------------

    private record Delegated(DelegationGrants grants, DelegationGrant grant, CallerAuthority authority,
                             AtomicInteger admissions) { }

    private Delegated delegated() {
        DelegationGrants grants = new DelegationGrants(new DelegationGrantStore(IdentityTestSupport.dataSource()),
                IdentityTestSupport.audit(new IdentityTestSupport.RecordingSink(), clock), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        String tenant = IdentityTestSupport.newTenant();
        DelegationGrant grant = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        Instant exp = clock.instant().plus(Duration.ofMinutes(5));
        VerifiedToken token = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of(AgentResourceAdmission.SCOPE),
                tenant, clock.instant(), exp, UUID.randomUUID().toString(), List.of(WORKFLOW.id()));
        InMemoryIssuedTokenBindings bindings = new InMemoryIssuedTokenBindings();
        bindings.bind(ISSUER, token.tokenId(), new IssuedTokenBindings.Binding(grant.id(), tenant, exp));
        AtomicInteger admissions = new AtomicInteger();
        ResourceTokenGuard guard = new ResourceTokenGuard((bearer, audience) -> {
            admissions.incrementAndGet();
            return token;
        }, AUDIENCE, new InMemoryReplayCache(100), grants, bindings, clock, 1);
        var calls = new ResourceCallFixtures.InMemory();
        calls.bind(ResourceCallFixtures.binding(token, grant.id(), ResourceCallFixtures.scope(tenant, ALICE, AUDIENCE,
                io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep.AGENT_RESEARCH_RUN,
                ResourceCallFixtures.TOPIC_REQUEST)));
        AgentResourceAdmission admission = new AgentResourceAdmission(guard, calls, bindings, AUDIENCE);
        var principal = admission.admit("a.b.c");
        return new Delegated(grants, grant, admission.authority(principal, clock), admissions);
    }

    @Test
    void aDelegatedTokenIsCurrentWhileItsGrantIsAndNeverConsumesASecondUse() {
        Delegated d = delegated();
        Set<String> scope = Set.of(AgentResourceAdmission.SCOPE);
        assertTrue(d.authority().delegated());
        d.authority().requireCurrent(scope);
        d.authority().requireCurrent(scope); // the single use was consumed once, at admission; rechecks do not replay
        assertEquals(1, d.admissions().get());
    }

    @Test
    void aGrantRevokedAfterAdmissionIsRefusedAtUse() {
        Delegated d = delegated();
        d.grants().revoke(d.grant().id(), ALICE, "revoked while the call blocked", null);
        assertEquals(Reason.REVOKED, refusal(d.authority(), Set.of(AgentResourceAdmission.SCOPE)));
    }

    @Test
    void aDelegatedTokenPastItsSignedExpiryIsRefusedAtUse() {
        Delegated d = delegated();
        clock.advance(Duration.ofMinutes(6));
        assertEquals(Reason.EXPIRED, refusal(d.authority(), Set.of(AgentResourceAdmission.SCOPE)));
    }

    @Test
    void aDelegatedTokenAskedForAScopeItNeverHeldIsRefused() {
        Delegated d = delegated();
        assertEquals(Reason.SCOPE, refusal(d.authority(), RECOVER));
    }

    @Test
    void unavailableIdentityDependenciesFailClosed() {
        VerifiedToken token = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of("oa.recover"), "tenant-a",
                clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), "j", List.of(WORKFLOW.id()));
        CallerAuthority down = CallerAuthority.ofDelegatedToken(token, clock, (t, s) -> {
            throw new TokenRejectedException(TokenRejectedException.Reason.VERIFIER_UNAVAILABLE);
        });
        assertEquals(Reason.UNAVAILABLE, refusal(down, RECOVER));
        CallerAuthority broken = CallerAuthority.ofDelegatedToken(token, clock, (t, s) -> {
            throw new IllegalStateException("database down");
        });
        assertEquals(Reason.UNAVAILABLE, refusal(broken, RECOVER));
    }

    @Test
    void aRecheckThatItselfCrossesTheExpiryIsRefused() {
        VerifiedToken token = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of("oa.recover"), "tenant-a",
                clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), "j", List.of(WORKFLOW.id()));
        CallerAuthority slow = CallerAuthority.ofDelegatedToken(token, clock,
                (t, s) -> clock.advance(Duration.ofMinutes(6)));
        assertEquals(Reason.EXPIRED, refusal(slow, RECOVER));
    }

    // ---- request doubles -----------------------------------------------------------------------------------------------

    private static jakarta.ws.rs.core.HttpHeaders headers(String subject, String scopes) {
        return proxy(name -> switch (name) {
            case "X-Tenant" -> "tenant-a";
            case "X-Subject" -> subject;
            case "X-Scopes" -> scopes;
            default -> null;
        }, null);
    }

    private static jakarta.ws.rs.core.HttpHeaders bearerHeaders() {
        return proxy(name -> null, List.of("Bearer a.b.c"));
    }

    private static jakarta.ws.rs.core.HttpHeaders proxy(java.util.function.Function<String, String> single,
                                                         List<String> authorization) {
        return (jakarta.ws.rs.core.HttpHeaders) java.lang.reflect.Proxy.newProxyInstance(
                jakarta.ws.rs.core.HttpHeaders.class.getClassLoader(),
                new Class<?>[] {jakarta.ws.rs.core.HttpHeaders.class}, (p, method, args) -> switch (method.getName()) {
                    case "getRequestHeader" -> authorization;
                    case "getHeaderString" -> single.apply((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
