package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.transaction.TransactionManager;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only process. Recovery phases have no graph/provider composition and never deserialize checkpoint data. */
public final class ResearchOutcomeRecoveryWorker {
    static final String TOPIC = "approved research";

    // The child uses plain JDBC, with no ambient JTA transaction. Real JTA suspension has separate boot coverage.
    static TransactionManager transactions() {
        return (TransactionManager) Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),
                new Class<?>[]{TransactionManager.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("suspend")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 || !arguments[0].matches("oa_re_facts_[0-9a-f]{32}"))
            throw new IllegalArgumentException("invalid research test database");
        var data = new PGSimpleDataSource();
        data.setURL(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        data.setDatabaseName(arguments[0]);
        data.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        data.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
        var manager = transactions();
        var binding = new JdbcResourceCallBindings(data, manager).findBinding(arguments[1]).orElseThrow();
        var entry = new ResourceExecutionEntry(binding.scope(), 1);
        String phase = arguments[2];
        var outcomes = new JdbcResearchOutcomes(data, manager, "a".repeat(64));
        if (phase.equals("graph-crash")) {
            var task = new JdbcResourceTaskAllocations(data, manager).find(binding.scope()).orElseThrow();
            var profile = task.allocation().modelProfile();
            var approved = new ApprovedModelContext.Approved(profile.agent(), task.allocation().contract(), "model", 5000);
            ApprovedModelContext context = () -> Optional.of(approved);
            var pep = new Pep(request -> new Decision("fixture", DecisionOutcome.ALLOW, "fixture",
                    new PolicyRef("fixture", "1"), Instant.now()), event -> { }, Duration.ofSeconds(5), Clock.systemUTC());
            ReservedLlmClient provider = new ReservedLlmClient() {
                public String configuredModelId() { return "model"; }
                public String reservedConfigurationFingerprint() { return "c".repeat(64); }
                public long maximumCostMicros(String prompt, int tokens) { return 999; }
                public String complete(String system, String user) { throw new AssertionError("unreserved provider call"); }
                public String completeReserved(String prompt, int tokens, Instant deadline) {
                    try (var connection = data.getConnection(); var insert = connection.createStatement()) {
                        insert.executeUpdate("insert into public.research_test_provider_attempt default values");
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                    return "{\"text\":\"answer\",\"actions\":[],\"escalation\":null}";
                }
            };
            var client = new GovernedLlmClient(provider, pep, context, new JdbcResourceModelCalls(data, manager));
            var agent = ResearchAgentTestFactory.create(data, client, context);
            var central = new AuditAppendOnce(data);
            try (var gate = new JdbcResourceExecutionGate(data, manager, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource(), central::appendOnce)) {
                var fence = JdbcResourceExecutionGate.CredentialFence.pair(binding, ignored -> { });
                var result = gate.openGoverned(binding, entry, UUID.randomUUID(), fence)
                        .executeGoverned(running -> agent.runGoverned(running, task, TOPIC));
                if (result.kind() != JdbcResourceExecutionGate.Kind.CALLED
                        || !"answer".equals(result.value().text(ResearchState.REVIEW).orElseThrow()))
                    throw new AssertionError("graph did not complete");
                Runtime.getRuntime().halt(77);
            }
        } else if (phase.equals("associate-crash") || phase.equals("recover")) {
            var outcome = outcomes.associate(entry);
            if (!outcome.equals(outcomes.verify(entry))) throw new AssertionError("outcome changed");
            if (phase.equals("associate-crash")) Runtime.getRuntime().halt(77);
        } else if (phase.equals("changed-refused")) {
            var retained = outcomes.find(entry).orElseThrow();
            refused(() -> outcomes.verify(entry));
            refused(() -> outcomes.associate(entry));
            if (!retained.equals(outcomes.find(entry).orElseThrow())) throw new AssertionError("history changed");
        } else throw new IllegalArgumentException("unknown research test phase");
    }

    private static void refused(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("changed checkpoint was accepted");
    }
}
