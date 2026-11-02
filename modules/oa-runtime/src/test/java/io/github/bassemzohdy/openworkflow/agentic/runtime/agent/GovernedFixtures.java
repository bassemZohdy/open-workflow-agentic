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
        return allowing(provider, GovernedLlmClientTest.APPROVED);
    }

    static GovernedLlmClient allowing(LlmClient provider, ApprovedModelContext context) {
        Pep pep = new Pep(
                request -> new Decision("test-engine", DecisionOutcome.ALLOW, "test", new PolicyRef("test", "1"),
                        GovernedLlmClientTest.NOW),
                event -> { },
                java.time.Duration.ofSeconds(5), GovernedLlmClientTest.CLOCK);
        LlmClient bound = new LlmClient() {
            @Override public String configuredModelId() { return "gpt-test"; }
            @Override public String complete(String system, String user) { return provider.complete(system, user); }
        };
        return new GovernedLlmClient(bound, pep, context);
    }

    /** Graph/storage test fixture: register a distinct approved task for each thread before running it. */
    static ResearchAgent budgetedAgent(javax.sql.DataSource data, LlmClient provider) {
        var current = new java.util.concurrent.atomic.AtomicReference<ApprovedModelContext.Approved>();
        ApprovedModelContext context = () -> java.util.Optional.ofNullable(current.get());
        var tasks = new java.util.HashMap<String, ApprovedModelContext.Approved>();
        var agent = new ResearchAgent() {
            @Override public synchronized ResearchState run(String thread, String topic) {
                CheckpointProfileVerifier.requireSupportedNamespace(checkpointSchema);
                CheckpointProfileVerifier.verify(dataSource);
                var approved = tasks.computeIfAbsent(thread, key -> {
                    var original = GovernedLlmClientTest.TASK;
                    var task = new io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract(
                            io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator.shared().next(),
                            original.goal(), original.permittedTools(), original.permittedDataScopes(), original.riskTier(),
                            original.limits(), original.requestingSubject(), original.delegationGrantId());
                    var value = new ApprovedModelContext.Approved(GovernedLlmClientTest.AGENT, task, "gpt-test", 500_000L);
                    nodeBudget.registerNew(key, value.agent(), task);
                    return value;
                });
                current.set(approved);
                return super.run(thread, topic);
            }
        };
        agent.approvedContext = context;
        agent.nodeBudget = new JdbcNodeBudget(data);
        agent.audit = event -> { };
        agent.llm = allowing(provider, context);
        return agent;
    }
}
