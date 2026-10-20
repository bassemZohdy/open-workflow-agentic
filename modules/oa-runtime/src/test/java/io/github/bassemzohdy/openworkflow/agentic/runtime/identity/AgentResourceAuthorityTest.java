package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.AgentResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP-103, GPT review of #47 (P1): a delegated credential admitted by the agent-run resource is judged again after the
 * policy decision and its mandatory audit, immediately before the graph starts. A slow audit that outlives the signed
 * expiry, or a grant revoked while it blocks, must leave zero graph work; the positive path is unchanged and the single
 * use is consumed once.
 */
class AgentResourceAuthorityTest {

    private static final String AUDIENCE = "oa-agent-run";
    private static final String ISSUER = "https://idp.test";
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "research-workflow");

    private MovableClock clock;
    private DelegationGrants grants;
    private DelegationGrant grant;
    private String tenant;
    private VerifiedToken token;
    private InMemoryIssuedTokenBindings bindings;
    private AtomicInteger graphRuns;
    private Runnable duringAudit;
    private AgentResource resource;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-19T10:00:00Z"));
        grants = new DelegationGrants(new DelegationGrantStore(IdentityTestSupport.dataSource()),
                IdentityTestSupport.audit(new IdentityTestSupport.RecordingSink(), clock), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        tenant = IdentityTestSupport.newTenant();
        grant = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        Instant exp = clock.instant().plus(Duration.ofMinutes(5));
        token = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of(AgentResourceAdmission.SCOPE), tenant,
                clock.instant(), exp, UUID.randomUUID().toString(), List.of(WORKFLOW.id()));
        bindings = new InMemoryIssuedTokenBindings();
        bindings.bind(ISSUER, token.tokenId(), new IssuedTokenBindings.Binding(grant.id(), tenant, exp));
        ResourceTokenGuard guard = new ResourceTokenGuard((bearer, audience) -> token, AUDIENCE,
                new InMemoryReplayCache(100), grants, bindings, clock, 1);
        graphRuns = new AtomicInteger();
        duringAudit = () -> { };
        ResearchAgent agent = new ResearchAgent() {
            @Override
            public ResearchState run(String threadId, String topic) {
                graphRuns.incrementAndGet();
                return new ResearchState(Map.of(ResearchState.TOPIC, topic));
            }
        };
        AuditSink slowAudit = new AuditSink() {
            @Override
            public void record(AuditEvent event) {
                duringAudit.run();
            }
        };
        Pep pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "ok",
                new PolicyRef("test.permit", "1"), clock.instant()), slowAudit);
        resource = new AgentResource(agent, null, pep, new ObjectMapper(),
                new AgentResourceAdmission(guard));
    }

    private static HttpHeaders bearer() {
        return (HttpHeaders) java.lang.reflect.Proxy.newProxyInstance(HttpHeaders.class.getClassLoader(),
                new Class<?>[] {HttpHeaders.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getRequestHeader")) {
                        return List.of("Bearer a.b.c");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private Map<String, Object> call() {
        return resource.run(bearer(), "{\"topic\":\"x\"}");
    }

    @Test
    void thePositivePathRunsTheGraphOnceAndConsumesTheSingleUseOnce() {
        assertEquals("x", call().get("topic"));
        assertEquals(1, graphRuns.get());
        // a replay of the same token is refused at admission and runs nothing
        assertEquals(401, assertThrows(WebApplicationException.class, this::call).getResponse().getStatus());
        assertEquals(1, graphRuns.get());
    }

    @Test
    void aSlowAuditThatOutlivesTheSignedExpiryRunsNoGraph() {
        duringAudit = () -> clock.advance(Duration.ofMinutes(6));
        assertEquals(401, assertThrows(WebApplicationException.class, this::call).getResponse().getStatus());
        assertEquals(0, graphRuns.get());
    }

    @Test
    void aGrantRevokedWhileThePolicyAuditBlocksRunsNoGraph() {
        duringAudit = () -> grants.revoke(grant.id(), ALICE, "revoked during audit", null);
        assertEquals(401, assertThrows(WebApplicationException.class, this::call).getResponse().getStatus());
        assertEquals(0, graphRuns.get());
    }
}
