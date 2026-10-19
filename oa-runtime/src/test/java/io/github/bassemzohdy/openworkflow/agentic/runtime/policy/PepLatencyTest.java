package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.AGENT;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.TASK_ID;
import static io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Decision latency in process, measured the way the WP-006 spike measured it: 2,000 warm-up calls, then 10,000 timed
 * calls, alternating an allow and a forbid. The figures are printed to the surefire log; the test asserts only that
 * every decision is the expected one, because timings on a shared host are not a pass/fail criterion.
 */
class PepLatencyTest {

    private static final int WARMUP = 2_000;
    private static final int CALLS = 10_000;

    private static final AuthorizationRequest ALLOW_CALL = new AuthorizationRequest(AGENT, ActionKind.TOOL_CALL,
            "tool:search", TASK_ID, new PolicyContext(Set.of("cases:read"), RiskTier.LOW, 5L, USER, null, true));
    private static final AuthorizationRequest DENY_CALL = new AuthorizationRequest(AGENT, ActionKind.TOOL_CALL,
            "tool:search", TASK_ID, new PolicyContext(Set.of("cases:read"), RiskTier.HIGH, 5L, USER, null, true));

    private static CedarPolicyDecisionPoint loaded() {
        CedarPolicyDecisionPoint pdp = new CedarPolicyDecisionPoint();
        pdp.install(PolicyFixtures.published(PolicyFixtures.V1, PolicyFixtures.referenceSet()));
        return pdp;
    }

    @Test
    void decisionLatencyThroughTheEnforcementPoint() {
        Pep pep = new Pep(loaded(), record -> { }, Pep.DEFAULT_TIMEOUT, Clock.systemUTC());
        long[] nanos = measure(pep::decide, true);
        report("pep-cedar-in-process", nanos);
    }

    @Test
    void decisionLatencyOfTheEngineAlone() {
        PolicyDecisionPoint pdp = loaded();
        long[] nanos = measure(pdp::decide, true);
        report("cedar-in-process", nanos);
    }

    @Test
    void snapshotInstallLatency() {
        CedarPolicyDecisionPoint pdp = new CedarPolicyDecisionPoint();
        List<String> texts = PolicyFixtures.referenceSet();
        long[] nanos = new long[200];
        for (int i = 0; i < nanos.length; i++) {
            PublishedPolicySnapshot published = PolicyFixtures.published(PolicyFixtures.V1, texts);
            long started = System.nanoTime();
            pdp.install(published);
            nanos[i] = System.nanoTime() - started;
        }
        report("snapshot-install", nanos);
    }

    private static long[] measure(java.util.function.Function<AuthorizationRequest, Decision> decide,
                                  boolean checkOutcome) {
        for (int i = 0; i < WARMUP; i++) {
            decide.apply(ALLOW_CALL);
            decide.apply(DENY_CALL);
        }
        long[] nanos = new long[CALLS];
        for (int i = 0; i < CALLS; i++) {
            boolean allow = i % 2 == 0;
            AuthorizationRequest request = allow ? ALLOW_CALL : DENY_CALL;
            long started = System.nanoTime();
            Decision d = decide.apply(request);
            nanos[i] = System.nanoTime() - started;
            if (checkOutcome) {
                assertEquals(allow, d.allows(), d.reason());
            }
        }
        return nanos;
    }

    private static void report(String label, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        System.out.printf("LATENCY %s n=%d p50=%.1fus p90=%.1fus p99=%.1fus max=%.1fus%n", label, sorted.length,
                us(percentile(sorted, 50)), us(percentile(sorted, 90)), us(percentile(sorted, 99)),
                us(sorted[sorted.length - 1]));
    }

    private static long percentile(long[] sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double us(long nanos) {
        return nanos / 1_000.0;
    }

    @Test
    void theLatencyHarnessUsesTheAgreedCallCounts() {
        assertEquals(10_000, CALLS);
        assertEquals(2_000, WARMUP);
        assertEquals(Duration.ofSeconds(1), Pep.DEFAULT_TIMEOUT);
    }
}
