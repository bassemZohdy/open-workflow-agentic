package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchRunInspector.Kind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The model boundary: a provider call happens only after an audited ALLOW, and every other path makes zero physical
 * provider calls. The "provider" here counts the calls that would have reached the network.
 */
class GovernedLlmClientTest {

    static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
    static final PolicyRef POLICY = new PolicyRef("model.test", "1");
    static final Subject AGENT = new Subject(SubjectKind.AGENT, "research-agent");
    static final Subject USER = new Subject(SubjectKind.USER, "bassem");
    static final String OK = "{\"text\":\"ok\",\"actions\":[],\"escalation\":null}";

    static final AgentTaskContract TASK = new AgentTaskContract(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
            "Research the topic", Set.of(), Set.of("cases:read"), RiskTier.LOW,
            new ExecutionLimits(20, Duration.ofMinutes(10), 1_000_000L), USER, Ulid.parse("01CP2ZZKBKACTAV9WEVGEMMVRZ"));

    static final ApprovedModelContext APPROVED = () -> Optional.of(
            new ApprovedModelContext.Approved(AGENT, TASK, "gpt-test", 500_000L));
    static final ApprovedModelContext NONE = Optional::empty;

    /** Counts the calls that reach the provider. */
    static final class CountingProvider implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final Function<Integer, String> answer;

        CountingProvider() {
            this(n -> OK);
        }

        CountingProvider(Function<Integer, String> answer) {
            this.answer = answer;
        }

        @Override
        public String complete(String system, String user) {
            return answer.apply(calls.incrementAndGet());
        }
    }

    static final class Audit implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();
        boolean failing;

        @Override
        public void record(AuditEvent event) {
            if (failing) {
                throw new IllegalStateException("audit store down");
            }
            events.add(event);
        }
    }

    static final class Engine implements PolicyDecisionPoint {
        final List<AuthorizationRequest> asked = new ArrayList<>();
        final Function<AuthorizationRequest, Decision> behaviour;

        Engine(DecisionOutcome outcome) {
            this(r -> new Decision("test-engine", outcome, "test", POLICY, NOW));
        }

        Engine(Function<AuthorizationRequest, Decision> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public synchronized Decision decide(AuthorizationRequest request) {
            asked.add(request);
            return behaviour.apply(request);
        }
    }

    static Pep pep(Engine engine, Audit audit) {
        return new Pep(engine, audit, Duration.ofSeconds(5), CLOCK);
    }

    // ------------------------------------------------------------------ allowed

    @Test
    void anAuditedAllowReachesTheProviderExactlyOnce() {
        var provider = new CountingProvider();
        var engine = new Engine(DecisionOutcome.ALLOW);
        var audit = new Audit();

        String answer = new GovernedLlmClient(provider, pep(engine, audit), APPROVED).complete("sys", "usr");

        assertEquals(OK, answer);
        assertEquals(1, provider.calls.get());
        assertEquals(1, engine.asked.size());
        assertEquals("model:gpt-test", engine.asked.get(0).resource());
        assertEquals(TASK.id(), engine.asked.get(0).taskId());
        assertEquals(1, audit.events.size(), "the decision is recorded before the provider is reached");
    }

    @Test
    void oneAllowAdmitsOneCallAndAProviderFailureIsNotRetried() {
        var provider = new CountingProvider(n -> {
            throw new IllegalStateException("provider down");
        });
        var client = new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ALLOW), new Audit()), APPROVED);

        assertThrows(IllegalStateException.class, () -> client.complete("sys", "usr"));
        assertEquals(1, provider.calls.get());
    }

    // ------------------------------------------------------------------ every refusal: zero provider calls

    @Test
    void denyMakesNoProviderCallAndIsAudited() {
        var provider = new CountingProvider();
        var audit = new Audit();
        var client = new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.DENY), audit), APPROVED);

        PolicyDenied refused = assertThrows(PolicyDenied.class, () -> client.complete("sys", "usr"));

        assertEquals(DecisionOutcome.DENY, refused.decision().outcome());
        assertEquals(0, provider.calls.get());
        assertEquals(1, audit.events.size(), "a refusal is recorded too");
    }

    @Test
    void anErrorDecisionMakesNoProviderCall() {
        var provider = new CountingProvider();
        var client = new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ERROR), new Audit()), APPROVED);

        PolicyDenied refused = assertThrows(PolicyDenied.class, () -> client.complete("sys", "usr"));

        assertEquals(DecisionOutcome.ERROR, refused.decision().outcome());
        assertEquals(0, provider.calls.get());
    }

    @Test
    void anEngineThatThrowsMakesNoProviderCall() {
        var provider = new CountingProvider();
        var engine = new Engine(r -> {
            throw new IllegalStateException("engine crashed");
        });
        var client = new GovernedLlmClient(provider, pep(engine, new Audit()), APPROVED);

        PolicyDenied refused = assertThrows(PolicyDenied.class, () -> client.complete("sys", "usr"));

        assertEquals(DecisionOutcome.ERROR, refused.decision().outcome());
        assertEquals(0, provider.calls.get());
    }

    @Test
    void anEngineThatAnswersNullMakesNoProviderCall() {
        var provider = new CountingProvider();
        var client = new GovernedLlmClient(provider, pep(new Engine(r -> null), new Audit()), APPROVED);

        assertThrows(PolicyDenied.class, () -> client.complete("sys", "usr"));
        assertEquals(0, provider.calls.get());
    }

    @Test
    void anAllowThatCannotBeAuditedMakesNoProviderCall() {
        var provider = new CountingProvider();
        var audit = new Audit();
        audit.failing = true;
        var client = new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ALLOW), audit), APPROVED);

        PolicyDenied refused = assertThrows(PolicyDenied.class, () -> client.complete("sys", "usr"));

        assertEquals(DecisionOutcome.ERROR, refused.decision().outcome());
        assertEquals(0, provider.calls.get(), "nothing is allowed without a record");
    }

    @Test
    void noApprovedContextMakesNoProviderCallAndAsksNoPolicyQuestion() {
        var provider = new CountingProvider();
        var engine = new Engine(DecisionOutcome.ALLOW);
        var client = new GovernedLlmClient(provider, pep(engine, new Audit()), NONE);

        assertThrows(ModelCallRefused.class, () -> client.complete("sys", "usr"));

        assertEquals(0, provider.calls.get());
        assertEquals(0, engine.asked.size(), "there is no task to put a policy question about");
    }

    @Test
    void aModelIdThePepCannotFormMakesNoProviderCall() {
        var provider = new CountingProvider();
        ApprovedModelContext blank = () -> Optional.of(
                new ApprovedModelContext.Approved(AGENT, TASK, " ", 1L));
        var client = new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ALLOW), new Audit()), blank);

        assertThrows(IllegalArgumentException.class, () -> client.complete("sys", "usr"));
        assertEquals(0, provider.calls.get());
    }

    @Test
    void theShippedDefaultsRefuseEverything() {
        // the default context has nothing approved; the model PEP with no snapshot cannot decide
        var provider = new CountingProvider();
        assertThrows(ModelCallRefused.class,
                () -> new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ALLOW), new Audit()),
                        new NoApprovedModelContext()).complete("s", "u"));

        var noSnapshot = ModelPolicyProducer.build(new Audit(), "policy/does-not-exist-" + UUID.randomUUID());
        PolicyDenied refused = assertThrows(PolicyDenied.class,
                () -> new GovernedLlmClient(provider, noSnapshot, APPROVED).complete("s", "u"));
        assertEquals(DecisionOutcome.ERROR, refused.decision().outcome());
        assertEquals(0, provider.calls.get());
    }

    // ------------------------------------------------------------------ through the real graph

    private static ResearchAgent agent(DataSource ds, GovernedLlmClient llm) throws Exception {
        ResearchAgent agent = new ResearchAgent();
        agent.llm = llm;
        agent.dataSource = ds;
        agent.outputValidator = new AgentOutputValidator(new ObjectMapper(), target -> false);
        agent.build();
        return agent;
    }

    @Test
    void aDeniedGraphRunMakesNoProviderCallAndLeavesNoCompletedRun() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var agent = agent(ds, new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.DENY), new Audit()), APPROVED));
        String thread = "governed-deny-" + UUID.randomUUID();

        assertThrows(Exception.class, () -> agent.run(thread, "topic"));

        assertEquals(0, provider.calls.get());
        var inspection = new ResearchRunInspector(ds).inspect(thread);
        assertTrue(inspection.kind() == Kind.PARTIAL || inspection.kind() == Kind.NONE,
                "a refused run is never complete: " + inspection.kind());
    }

    @Test
    void anAllowedGraphRunMakesOneAuditedProviderCallPerNode() throws Exception {
        DataSource ds = AgentTestDatabase.dataSource();
        var provider = new CountingProvider();
        var audit = new Audit();
        var agent = agent(ds, new GovernedLlmClient(provider, pep(new Engine(DecisionOutcome.ALLOW), audit), APPROVED));
        String thread = "governed-allow-" + UUID.randomUUID();

        var state = agent.run(thread, "topic");

        assertSame(Kind.COMPLETE, new ResearchRunInspector(ds).inspect(thread).kind());
        assertEquals(3, provider.calls.get(), "plan, draft and review");
        assertEquals(3, audit.events.size(), "each provider call has its own audited decision");
        assertTrue(state.text(ResearchState.REVIEW).isPresent());
    }
}
