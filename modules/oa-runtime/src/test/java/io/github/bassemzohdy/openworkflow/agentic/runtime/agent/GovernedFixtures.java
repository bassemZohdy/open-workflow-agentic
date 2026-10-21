package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;

/**
 * Test-only: a governed client that allows every call, for tests of the graph and of storage that are not about the
 * model boundary. Production has no such object: the default context approves nothing.
 */
final class GovernedFixtures {

    private GovernedFixtures() {
    }

    static GovernedLlmClient allowing(LlmClient provider) {
        Pep pep = new Pep(
                request -> new Decision("test-engine", DecisionOutcome.ALLOW, "test", new PolicyRef("test", "1"),
                        GovernedLlmClientTest.NOW),
                event -> { },
                java.time.Duration.ofSeconds(5), GovernedLlmClientTest.CLOCK);
        return new GovernedLlmClient(provider, pep, GovernedLlmClientTest.APPROVED);
    }
}
