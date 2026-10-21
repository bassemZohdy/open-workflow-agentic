package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchState;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.AgentResource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP-103, GPT review of #53 (P1): an ordinary user's token is judged again after policy, mandatory audit and parsing,
 * immediately before the graph starts, like a delegated one. The classes are the real resolver, resource and PEP; the
 * verifier, audit sink and graph are doubles. Also the golden cases of the canonical request renderer.
 */
class AgentResourceUserFreshnessTest {

    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");

    private MovableClock clock;
    private AtomicInteger graphRuns;
    private Runnable duringAudit;
    private AgentResource resource;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
        graphRuns = new AtomicInteger();
        duringAudit = () -> { };
        VerifiedToken token = new VerifiedToken("https://idp.test", ALICE, Set.of("oa-api"),
                Set.of(AgentResourceAdmission.SCOPE), "tenant-a", clock.instant(),
                clock.instant().plus(Duration.ofMinutes(5)), "jti-user", List.of());
        ResearchAgent agent = new ResearchAgent() {
            @Override
            public ResearchState run(String threadId, String topic) {
                graphRuns.incrementAndGet();
                return new ResearchState(Map.of(ResearchState.TOPIC, topic));
            }
        };
        Pep pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "ok", new PolicyRef("test.permit", "1"),
                clock.instant()), (AuditEvent event) -> duringAudit.run());
        resource = new AgentResource(agent, new CallerContextResolver((bearer, audience) -> token, "oa-api", false,
                Set.of("test"), Set.of("test"), clock), pep, new ObjectMapper(), new AgentResourceAdmission(null, null,
                null, null, clock));
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

    @Test
    void aUserTokenThatExpiresWhileThePolicyAuditBlocksRunsNoGraph() {
        duringAudit = () -> clock.advance(Duration.ofMinutes(6));
        var refused = assertThrows(WebApplicationException.class, () -> resource.run(bearer(), "{\"topic\":\"x\"}"));
        assertEquals(401, refused.getResponse().getStatus());
        assertEquals(0, graphRuns.get());
    }

    @Test
    void aStillValidUserTokenRunsTheGraphOnce() {
        assertEquals("x", resource.run(bearer(), "{\"topic\":\"x\"}").get("topic"));
        assertEquals(1, graphRuns.get());
    }

    @Test
    void theCanonicalRequestIsCompactAndIndependentOfAnyJsonLibrary() {
        assertEquals("{\"topic\":\"agentic workflows\"}", AgentResource.canonicalRequest("agentic workflows"));
        assertEquals("{\"topic\":\"say \\\"hi\\\" \\\\ back\"}", AgentResource.canonicalRequest("say \"hi\" \\ back"));
        assertEquals("{\"topic\":\"a\\nb\\rc\\td\\be\\ff\"}", AgentResource.canonicalRequest("a\nb\rc\td\be\ff"));
        assertEquals("{\"topic\":\"\\u0000\\u001f\"}", AgentResource.canonicalRequest("\u0000\u001f"));
        assertEquals("{\"topic\":\"/\u007f\"}", AgentResource.canonicalRequest("/\u007f"));
        assertEquals("{\"topic\":\"café 日本語 🚀\"}",
                AgentResource.canonicalRequest("café 日本語 🚀"));
        assertEquals("{\"topic\":\" \"}", AgentResource.canonicalRequest(" "));
        assertThrows(IllegalArgumentException.class, () -> AgentResource.canonicalRequest("lone \ud83d surrogate"));
        assertThrows(IllegalArgumentException.class, () -> AgentResource.canonicalRequest("lone \ude80 surrogate"));
    }
}
