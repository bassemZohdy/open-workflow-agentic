package spike.d;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cedar (cedar-java 4.10.0, in process): tool-call authorization, default deny and fail closed. */
class CedarToolAuthzTest {

    private CedarPdp pdp;

    @BeforeEach
    void loadShippedPolicies() throws IOException {
        pdp = new CedarPdp();
        try (InputStream in = CedarToolAuthzTest.class.getResourceAsStream("/policies/tool-calls.cedar")) {
            pdp.load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static Pdp.Request request(String tool, Set<String> scopes, long budget, boolean approved) {
        return new Pdp.Request("agent-42", "bassem", tool, scopes, budget, approved);
    }

    @Test
    void permitsScopedBudgetedLowRiskCall() {
        assertTrue(pdp.decide(request("search", Set.of("cases:read"), 5, false)).allowed());
    }

    @Test
    void defaultDenyWhenScopeMissing() {
        // close_case needs cases:write; with only cases:read no permit matches.
        assertFalse(pdp.decide(request("close_case", Set.of("cases:read"), 5, true)).allowed());
    }

    @Test
    void defaultDenyWhenBudgetExhausted() {
        assertFalse(pdp.decide(request("search", Set.of("cases:read"), 0, false)).allowed());
    }

    @Test
    void forbidOverridesPermitForHighRiskWithoutApproval() {
        Pdp.Decision d = pdp.decide(request("close_case", Set.of("cases:write"), 5, false));
        assertFalse(d.allowed(), d.reason());
        // The reason names the policy that produced it. Cedar 4.10 reports engine-assigned ids
        // ("policy1" = second policy in the file), not the @id annotation. Attribution therefore needs
        // our own id map (open question in the spike report).
        assertTrue(d.reason().contains("policy1"), d.reason());
    }

    @Test
    void allowDecisionNamesThePermitPolicy() {
        Pdp.Decision d = pdp.decide(request("search", Set.of("cases:read"), 5, false));
        assertTrue(d.reason().contains("policy0"), d.reason());
    }

    @Test
    void highRiskPermittedOnlyWithRecordedApproval() {
        assertTrue(pdp.decide(request("close_case", Set.of("cases:write"), 5, true)).allowed());
    }

    @Test
    void unknownToolIsDenied() {
        // No Tool entity: the permit's attribute access errors, so it does not match.
        Pdp.Decision d = pdp.decide(request("drop_database", Set.of("cases:read", "cases:write"), 5, true));
        assertFalse(d.allowed(), d.reason());
    }

    @Test
    void malformedPolicyTextFailsClosed() {
        pdp.load("permit(principal, action, resource");
        Pdp.Decision d = pdp.decide(request("search", Set.of("cases:read"), 5, false));
        assertFalse(d.allowed());
        assertTrue(d.reason().startsWith("fail closed"), d.reason());
    }

    @Test
    void emptyPolicySetDeniesEverything() {
        pdp.load("");
        assertFalse(pdp.decide(request("search", Set.of("cases:read"), 5, false)).allowed());
    }

    @Test
    void policyEvaluationErrorIsNotAnAllow() {
        // A permit whose condition errors at evaluation time (missing context attribute).
        pdp.load("permit(principal, action, resource) when { context.doesNotExist == 1 };");
        assertFalse(pdp.decide(request("search", Set.of("cases:read"), 5, false)).allowed());
    }

    @Test
    void unconfiguredPdpDeniesBeforeLoading() {
        Pdp unloaded = new CedarPdp();
        Pdp.Decision d = unloaded.decide(request("search", Set.of("cases:read"), 5, false));
        assertFalse(d.allowed());
        assertTrue(d.reason().startsWith("fail closed"), d.reason());
    }
}
