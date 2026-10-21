package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP-103 (GPT's #49 contract): admission loads the durable resource-call binding by the VERIFIED issuer and token id and
 * compares it with the token, its grant and this resource. A missing binding, a disagreement or an unavailable store
 * refuses; an unconfigured store refuses too (an unbound credential is never accepted).
 */
class AgentResourceBindingAdmissionTest {

    private static final String AUDIENCE = "oa-agent-run";
    private static final String ISSUER = "https://idp.test";
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "research-workflow");

    private MovableClock clock;
    private String tenant;
    private DelegationGrant grant;
    private VerifiedToken token;
    private InMemoryIssuedTokenBindings issued;
    private ResourceTokenGuard guard;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
        DelegationGrants grants = new DelegationGrants(new DelegationGrantStore(IdentityTestSupport.dataSource()),
                IdentityTestSupport.audit(new IdentityTestSupport.RecordingSink(), clock), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        tenant = IdentityTestSupport.newTenant();
        grant = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        Instant exp = clock.instant().plus(Duration.ofMinutes(5));
        token = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of(AgentResourceAdmission.SCOPE), tenant,
                clock.instant(), exp, UUID.randomUUID().toString(), List.of(WORKFLOW.id()));
        issued = new InMemoryIssuedTokenBindings();
        issued.bind(ISSUER, token.tokenId(), new IssuedTokenBindings.Binding(grant.id(), tenant, exp));
        guard = new ResourceTokenGuard((bearer, audience) -> token, AUDIENCE, new InMemoryReplayCache(100), grants,
                issued, clock, 1);
    }

    private Reason refusal(ResourceCallBindings calls) {
        return assertThrows(TokenRejectedException.class,
                () -> new AgentResourceAdmission(guard, calls, issued, AUDIENCE, clock).admit("a.b.c")).reason();
    }

    private ResourceCallBindings bound(String callTenant, Subject owner, String audience, String operation) {
        var calls = new ResourceCallFixtures.InMemory();
        calls.bind(ResourceCallFixtures.binding(token, grant.id(), ResourceCallFixtures.scope(callTenant, owner,
                audience, operation, ResourceCallFixtures.TOPIC_REQUEST)));
        return calls;
    }

    @Test
    void anAgreeingBindingAdmitsAndCarriesTheInvocation() {
        var calls = bound(tenant, ALICE, AUDIENCE, Pep.AGENT_RESEARCH_RUN);
        var principal = new AgentResourceAdmission(guard, calls, issued, AUDIENCE, clock).admit("a.b.c");
        assertEquals(calls.find(ISSUER, token.tokenId()).orElseThrow(), principal.call());
    }

    @Test
    void noStoreOrNoBindingRefusesAndNothingIsDefaulted() {
        assertEquals(Reason.BINDING_MISSING, refusal(null));
    }

    @Test
    void anEmptyStoreRefuses() {
        assertEquals(Reason.BINDING_MISSING, refusal(new ResourceCallFixtures.InMemory()));
    }

    @Test
    void aStoreThatCannotAnswerFailsClosedAsUnavailable() {
        assertEquals(Reason.VERIFIER_UNAVAILABLE, refusal(new ResourceCallFixtures.Down()));
    }

    @Test
    void aBindingThatDisagreesWithTheTokenOrThisResourceIsRefused() {
        assertEquals(Reason.BINDING_MISMATCH,
                refusal(bound(tenant, new Subject(SubjectKind.USER, "mallory"), AUDIENCE, Pep.AGENT_RESEARCH_RUN)));
        setUp();
        assertEquals(Reason.BINDING_MISMATCH, refusal(bound("other-" + tenant, ALICE, AUDIENCE,
                Pep.AGENT_RESEARCH_RUN)));
        setUp();
        assertEquals(Reason.BINDING_MISMATCH, refusal(bound(tenant, ALICE, "another-audience",
                Pep.AGENT_RESEARCH_RUN)));
        setUp();
        assertEquals(Reason.BINDING_MISMATCH, refusal(bound(tenant, ALICE, AUDIENCE, "agent:other:run")));
    }

    @Test
    void aBindingOfAnotherGrantThanTheIssuingOneIsRefused() {
        var calls = new ResourceCallFixtures.InMemory();
        calls.bind(ResourceCallFixtures.binding(token, UlidGenerator.shared().next(), ResourceCallFixtures.scope(tenant,
                ALICE, AUDIENCE, Pep.AGENT_RESEARCH_RUN, ResourceCallFixtures.TOPIC_REQUEST)));
        assertEquals(Reason.BINDING_MISMATCH, refusal(calls));
    }
}
