package io.github.bassemzohdy.openworkflow.agentic.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ApprovedModelContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClient;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LlmClient;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ModelCallRefused;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.NoApprovedModelContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrantStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityAudit;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JdbcIssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.CredentialFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.RefusedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.Running;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StartRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * WP-103 mechanics: the controlled {@code GovernedLlmClient.complete(Running, entry, system, user)} against a REAL
 * private-minted {@link Running} from a fresh committed gate callback in a private PostgreSQL database.
 *
 * <p>FIXTURE ONLY: the fence, approved context, policy engine, audit sink and provider below are mechanics, not
 * production admission, identity, reservation or task mapping. No approved-context source, workload profile, lease or
 * task-to-call mapping exists; this proves only that the same control is rechecked around blocking work. Every worker
 * thread is joined before the gate callback returns.
 */
class GovernedModelRunningControlTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final PolicyRef POLICY = new PolicyRef("model.fixture", "1");
    private static final Subject USER = new Subject(SubjectKind.USER, "fixture-user");
    private static final Subject AGENT = new Subject(SubjectKind.AGENT, "fixture-agent");
    private static final Subject WORKLOAD = new Subject(SubjectKind.WORKFLOW, "fixture-workload");
    private static final String SECRET = "private-provider-output";
    private static final AgentTaskContract TASK = new AgentTaskContract(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
            "fixture task", Set.of(), Set.of("cases:read"), RiskTier.LOW,
            new ExecutionLimits(20, Duration.ofMinutes(10), 1_000_000L), USER, Ulid.parse("01CP2ZZKBKACTAV9WEVGEMMVRZ"));

    private static Db db;

    @BeforeAll
    static void createPrivateDatabase() throws Exception {
        db = new Db();
    }

    @AfterAll
    static void dropPrivateDatabase() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------ mechanics

    /** Counts every call that would reach the network; {@code during} runs while the "network" is in progress. */
    static final class Provider implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile Runnable during = () -> { };

        @Override
        public String complete(String system, String user) {
            calls.incrementAndGet();
            during.run();
            return SECRET;
        }
    }

    static final class Audit implements AuditSink {
        final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());
        volatile Runnable during = () -> { };

        @Override
        public void record(AuditEvent event) {
            during.run();
            events.add(event);
        }
    }

    static final class Engine implements PolicyDecisionPoint {
        final AtomicInteger asked = new AtomicInteger();
        volatile Runnable during = () -> { };
        final DecisionOutcome outcome;

        Engine(DecisionOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Decision decide(AuthorizationRequest request) {
            asked.incrementAndGet();
            during.run();
            return new Decision("fixture-engine", outcome, "fixture", POLICY, NOW);
        }
    }

    /** Fixture approved context; counts lookups and may block or act inside the lookup. */
    static final class Context implements ApprovedModelContext {
        final AtomicInteger lookups = new AtomicInteger();
        volatile Runnable during = () -> { };
        final long budget;

        Context(long budget) {
            this.budget = budget;
        }

        @Override
        public Optional<Approved> current() {
            lookups.incrementAndGet();
            during.run();
            return Optional.of(new Approved(AGENT, TASK, "fixture-model", budget));
        }
    }

    /** One scenario over one fixture context. */
    static final class Rig {
        final Provider provider = new Provider();
        final Audit audit = new Audit();
        final Engine engine;
        final Context context;
        final GovernedLlmClient client;

        Rig(DecisionOutcome outcome, long budget) {
            engine = new Engine(outcome);
            context = new Context(budget);
            client = new GovernedLlmClient(provider, new Pep(engine, audit, Duration.ofSeconds(5), CLOCK), context);
        }

        Rig() {
            this(DecisionOutcome.ALLOW, 500_000L);
        }

        void assertUntouched() {
            assertEquals(0, context.lookups.get(), "no approved-context lookup");
            assertEquals(0, engine.asked.get(), "no policy question");
            assertEquals(0, audit.events.size(), "no audit record");
            assertEquals(0, provider.calls.get(), "no provider call");
        }
    }

    @FunctionalInterface
    interface Body<T> {
        T run(Running running, ResourceExecutionEntry entry, AtomicInteger fenceChecks) throws Exception;
    }

    /**
     * Runs {@code body} inside a fresh committed governed callback of a real gate. The fixture fence counts its
     * checks and can be flipped to refuse. The callback's value is returned; its exception propagates.
     */
    private static <T> T inCallback(AtomicReference<Boolean> fenceOpen, Body<T> body) throws Exception {
        var f = fixture(db);
        var checks = new AtomicInteger();
        try (var gate = new JdbcResourceExecutionGate(db.owner, 1)) {
            var fence = CredentialFence.pair(f.binding, entry -> {
                checks.incrementAndGet();
                if (!fenceOpen.get()) {
                    throw new IllegalStateException("fixture fence refuses");
                }
            });
            var attempt = gate.openGoverned(f.binding, f.entry(1), UUID.randomUUID(), fence);
            var result = attempt.executeGoverned(running -> {
                try {
                    return body.run(running, f.entry(1), checks);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertEquals(JdbcResourceExecutionGate.Kind.CALLED, result.kind());
            assertEquals(0, gate.activeCount());
            return result.value();
        }
    }

    private static <T> T inCallback(Body<T> body) throws Exception {
        return inCallback(new AtomicReference<>(true), body);
    }

    /** A callback that closes its own control ends refused after the body returns, which is correct, not a failure. */
    private static void inClosingCallback(Body<Void> body) {
        assertThrows(RefusedException.class, () -> inCallback(body));
    }

    // ------------------------------------------------------------------ the positive path

    @Test
    void aLiveRunningControlAdmitsExactlyOneProviderCallAndReleasesTheOutput() throws Exception {
        var rig = new Rig();

        String out = inCallback((running, entry, checks) -> rig.client.complete(running, entry, "sys", "usr"));

        assertEquals(SECRET, out);
        assertEquals(1, rig.provider.calls.get(), "exactly one provider call");
        assertEquals(1, rig.audit.events.size(), "the ALLOW was audited");
        assertEquals("ALLOW", rig.audit.events.get(0).attributes().get("policy.outcome"));
    }

    // ------------------------------------------------------------------ before lookup

    @Test
    void aControlThatHasEndedPreventsLookupPolicyAuditAndProvider() throws Exception {
        var rig = new Rig();
        var captured = new AtomicReference<Running>();
        var entryOf = new AtomicReference<ResourceExecutionEntry>();
        inCallback((running, entry, checks) -> {
            captured.set(running);
            entryOf.set(entry);
            return null;
        });

        var late = assertThrows(RefusedException.class,
                () -> rig.client.complete(captured.get(), entryOf.get(), "sys", "usr"));
        assertTrue(late.getMessage().contains("not executing"), late.getMessage());
        rig.assertUntouched();
    }

    @Test
    void aControlClosedInsideTheCallbackBeforeTheCallPreventsEverything() {
        var rig = new Rig();
        inClosingCallback((running, entry, checks) -> {
            running.close();
            assertThrows(RefusedException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            rig.assertUntouched();
            return null;
        });
    }

    @Test
    void anInterruptedCallerIsRefusedBeforeLookup() throws Exception {
        var rig = new Rig();
        inCallback((running, entry, checks) -> {
            Thread.currentThread().interrupt();
            try {
                assertThrows(RefusedException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            } finally {
                assertTrue(Thread.interrupted(), "the flag was the refusal reason; clear it for the gate");
            }
            rig.assertUntouched();
            return null;
        });
    }

    @Test
    void aFenceThatRefusesBeforeTheCallPreventsEverything() throws Exception {
        var rig = new Rig();
        var open = new AtomicReference<>(true);
        inCallback(open, (running, entry, checks) -> {
            open.set(false);
            assertThrows(IllegalStateException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            rig.assertUntouched();
            open.set(true);
            return null;
        });
    }

    // ------------------------------------------------------------------ during and after the blocking lookup

    @Test
    void aControlClosedDuringTheContextLookupAsksNoPolicyAndCallsNoProvider() {
        var rig = new Rig();
        inClosingCallback((running, entry, checks) -> {
            rig.context.during = running::close;
            assertThrows(RefusedException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            assertEquals(1, rig.context.lookups.get());
            assertEquals(0, rig.engine.asked.get(), "no policy question after the lookup lost the control");
            assertEquals(0, rig.audit.events.size());
            assertEquals(0, rig.provider.calls.get());
            return null;
        });
    }

    @Test
    void aFenceLostDuringTheContextLookupAsksNoPolicyAndCallsNoProvider() throws Exception {
        var rig = new Rig();
        var open = new AtomicReference<>(true);
        inCallback(open, (running, entry, checks) -> {
            rig.context.during = () -> open.set(false);
            assertThrows(IllegalStateException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            assertEquals(0, rig.engine.asked.get());
            assertEquals(0, rig.provider.calls.get());
            open.set(true);
            return null;
        });
    }

    // ------------------------------------------------------------------ blocking PEP and audit

    @Test
    void aControlClosedDuringThePolicyDecisionNeverReachesTheProvider() {
        var rig = new Rig();
        inClosingCallback((running, entry, checks) -> {
            rig.engine.during = running::close;
            assertThrows(RefusedException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            assertEquals(1, rig.engine.asked.get());
            assertEquals(0, rig.provider.calls.get(), "an ALLOW decided under a lost control is not honoured");
            return null;
        });
    }

    @Test
    void aControlClosedDuringTheAuditRecordNeverReachesTheProvider() {
        var rig = new Rig();
        inClosingCallback((running, entry, checks) -> {
            rig.audit.during = running::close;
            assertThrows(RefusedException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            assertEquals(1, rig.audit.events.size(), "the audited ALLOW exists but is not enough");
            assertEquals(0, rig.provider.calls.get());
            return null;
        });
    }

    // ------------------------------------------------------------------ blocking provider and output

    @Test
    void aControlClosedWhileTheProviderRunsWithholdsItsPrivateOutput() {
        var rig = new Rig();
        inClosingCallback((running, entry, checks) -> {
            rig.provider.during = running::close;
            var thrown = assertThrows(RefusedException.class,
                    () -> rig.client.complete(running, entry, "sys", "usr"));
            assertEquals(1, rig.provider.calls.get(), "the provider ran once; its output was withheld");
            assertFalse(String.valueOf(thrown.getMessage()).contains(SECRET));
            return null;
        });
    }

    @Test
    void aFenceLostWhileTheProviderRunsWithholdsItsPrivateOutput() throws Exception {
        var rig = new Rig();
        var open = new AtomicReference<>(true);
        inCallback(open, (running, entry, checks) -> {
            rig.provider.during = () -> open.set(false);
            assertThrows(IllegalStateException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            assertEquals(1, rig.provider.calls.get());
            open.set(true);
            return null;
        });
    }

    @Test
    void aCallerInterruptedWhileTheProviderRunsWithholdsItsPrivateOutput() throws Exception {
        var rig = new Rig();
        inCallback((running, entry, checks) -> {
            rig.provider.during = () -> Thread.currentThread().interrupt();
            try {
                assertThrows(RefusedException.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            } finally {
                Thread.interrupted();
            }
            assertEquals(1, rig.provider.calls.get());
            return null;
        });
    }

    // ------------------------------------------------------------------ exact entry

    @Test
    void aMismatchedExpectedEntryPreventsEverythingEvenWithALiveControl() throws Exception {
        var rig = new Rig();
        inCallback((running, entry, checks) -> {
            var otherEpoch = new ResourceExecutionEntry(entry.scope(), entry.epoch() + 1);
            assertThrows(RefusedException.class, () -> rig.client.complete(running, otherEpoch, "sys", "usr"));
            rig.assertUntouched();
            // the control itself is still live and exact for the real entry
            assertEquals(SECRET, rig.client.complete(running, entry, "sys", "usr"));
            return null;
        });
        assertEquals(1, rig.provider.calls.get());
    }

    // ------------------------------------------------------------------ absent control, defaults, legacy

    @Test
    void anAbsentControlOrEntryIsRefusedBeforeAnything() throws Exception {
        var rig = new Rig();
        inCallback((running, entry, checks) -> {
            assertThrows(ModelCallRefused.class, () -> rig.client.complete(null, entry, "sys", "usr"));
            assertThrows(ModelCallRefused.class, () -> rig.client.complete(running, null, "sys", "usr"));
            rig.assertUntouched();
            return null;
        });
    }

    @Test
    void theDefaultContextStillRefusesAndAControlIsNotAnApprovedContextSource() throws Exception {
        var provider = new Provider();
        var engine = new Engine(DecisionOutcome.ALLOW);
        var audit = new Audit();
        var client = new GovernedLlmClient(provider, new Pep(engine, audit, Duration.ofSeconds(5), CLOCK),
                new NoApprovedModelContext());
        inCallback((running, entry, checks) -> {
            assertThrows(ModelCallRefused.class, () -> client.complete(running, entry, "sys", "usr"));
            return null;
        });
        assertEquals(0, provider.calls.get());
        assertEquals(0, engine.asked.get());
        assertEquals(0, audit.events.size());
    }

    @Test
    void aZeroBudgetStillHardRefusesWithALiveControlAndAPermissivePolicy() throws Exception {
        var rig = new Rig(DecisionOutcome.ALLOW, 0L);
        inCallback((running, entry, checks) -> {
            assertThrows(ModelCallRefused.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            return null;
        });
        assertEquals(1, rig.context.lookups.get());
        assertEquals(0, rig.engine.asked.get());
        assertEquals(0, rig.audit.events.size());
        assertEquals(0, rig.provider.calls.get());
    }

    @Test
    void aDenyUnderALiveControlIsAuditedAndNeverReachesTheProvider() throws Exception {
        var rig = new Rig(DecisionOutcome.DENY, 500_000L);
        inCallback((running, entry, checks) -> {
            assertThrows(PolicyDenied.class, () -> rig.client.complete(running, entry, "sys", "usr"));
            return null;
        });
        assertEquals(1, rig.audit.events.size());
        assertEquals(0, rig.provider.calls.get());
    }

    /** BASELINE, not a feature: the legacy two-argument path carries no control, so it cannot honour a closed one. */
    @Test
    void baselineTheLegacyPathIgnoresAClosedControlWhichTheControlledOverloadRefuses() {
        var legacy = new Rig();
        var controlled = new Rig();
        inClosingCallback((running, entry, checks) -> {
            running.close();
            assertEquals(SECRET, legacy.client.complete("sys", "usr"), "legacy behaviour is unchanged");
            assertThrows(RefusedException.class, () -> controlled.client.complete(running, entry, "sys", "usr"));
            return null;
        });
        assertEquals(1, legacy.provider.calls.get());
        assertEquals(0, controlled.provider.calls.get());
    }

    // ------------------------------------------------------------------ cross-thread SAME handle

    @Test
    void theSameExplicitHandleWorksOnAnotherThreadAndIsJoinedBeforeTheCallbackReturns() throws Exception {
        var rig = new Rig();
        var callbackThread = new AtomicReference<Thread>();
        var workerThread = new AtomicReference<Thread>();
        String out = inCallback((running, entry, checks) -> {
            callbackThread.set(Thread.currentThread());
            var pool = Executors.newSingleThreadExecutor();
            try {
                return pool.submit(() -> {
                    workerThread.set(Thread.currentThread());
                    return rig.client.complete(running, entry, "sys", "usr");
                }).get(15, TimeUnit.SECONDS);
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "worker joined before callback returns");
            }
        });
        assertEquals(SECRET, out);
        assertNotSame(callbackThread.get(), workerThread.get());
        assertEquals(1, rig.provider.calls.get());
    }

    @Test
    void closingTheSameHandleFromTheCallbackThreadWithholdsAWorkersBlockedProviderOutput() {
        var rig = new Rig();
        var inProvider = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rig.provider.during = () -> {
            inProvider.countDown();
            assertDoesNotThrow(() -> assertTrue(release.await(15, TimeUnit.SECONDS)));
        };
        inClosingCallback((running, entry, checks) -> {
            var pool = Executors.newSingleThreadExecutor();
            try {
                var work = pool.submit(() -> rig.client.complete(running, entry, "sys", "usr"));
                assertTrue(inProvider.await(15, TimeUnit.SECONDS));
                running.close();
                release.countDown();
                var failure = assertThrows(ExecutionException.class, () -> work.get(15, TimeUnit.SECONDS));
                assertInstanceOf(RefusedException.class, failure.getCause());
                assertEquals(1, rig.provider.calls.get());
            } finally {
                release.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "worker joined before callback returns");
            }
            return null;
        });
    }

    @Test
    void aWorkerBlockedInTheContextLookupSeesAHandleClosedByTheCallbackThread() {
        var rig = new Rig();
        var inLookup = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        rig.context.during = () -> {
            inLookup.countDown();
            assertDoesNotThrow(() -> assertTrue(release.await(15, TimeUnit.SECONDS)));
        };
        inClosingCallback((running, entry, checks) -> {
            var pool = Executors.newSingleThreadExecutor();
            try {
                var work = pool.submit(() -> rig.client.complete(running, entry, "sys", "usr"));
                assertTrue(inLookup.await(15, TimeUnit.SECONDS));
                running.close();
                release.countDown();
                var failure = assertThrows(ExecutionException.class, () -> work.get(15, TimeUnit.SECONDS));
                assertInstanceOf(RefusedException.class, failure.getCause());
                assertEquals(0, rig.engine.asked.get());
                assertEquals(0, rig.provider.calls.get());
            } finally {
                release.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "worker joined before callback returns");
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ private database fixture

    record Fixture(ResourceCallBinding binding) {
        ResourceExecutionEntry entry(long epoch) {
            return new ResourceExecutionEntry(binding.scope(), epoch);
        }
    }

    /** A fresh instance row, delegation grant and credential binding: DATA the real gate requires, not admission. */
    private static Fixture fixture(Db db) throws SQLException {
        var registry = new JdbcInstanceStore(db.owner);
        var ids = UlidGenerator.shared();
        String tenant = "gm-" + UUID.randomUUID();
        var request = new StartRequest(tenant, USER, new DefinitionRef("agentic/model-boundary-test", "1"), Map.of(),
                "start-" + UUID.randomUUID());
        var row = registry.insertIfAbsent(InstanceRecord.starting(ids.next(), request, now()), "test.start").record();
        row = registry.replace(row.successor(now(), InstanceStatus.RUNNING, "engine-" + row.id(), 1, null, null),
                row.version(), "test.running", USER);
        var scope = new ResourceCallScope(tenant, row.id(), USER, "app-model", row.engineInstanceId(), "agentic",
                "model-boundary-test", "1", "/do/0/model", 1, "root/model", "agent-run", Pep.AGENT_RESEARCH_RUN,
                "a".repeat(64), "b".repeat(64));
        var clock = Clock.systemUTC();
        var grants = new DelegationGrants(new DelegationGrantStore(db.owner),
                new IdentityAudit(event -> { }, clock, ids), clock, ids, Duration.ofHours(2));
        var grant = grants.grant(tenant, USER, USER, WORKLOAD, Set.of("oa.agent.run"), now().plusSeconds(3600), null);
        var issued = db.databaseNow().minusSeconds(1);
        var expires = issued.plusSeconds(120);
        String issuer = "https://model-boundary-idp.test";
        String jti = UUID.randomUUID().toString();
        new JdbcIssuedTokenBindings(db.owner).bind(issuer, jti,
                new IssuedTokenBindings.Binding(grant.id(), tenant, expires));
        var binding = new ResourceCallBinding(issuer, jti, grant.id(), scope, issued, expires);
        new JdbcResourceCallBindings(db.owner).bind(binding);
        return new Fixture(binding);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** One owned UUID database: created and migrated here, dropped (never FORCE) after the class. */
    static final class Db implements AutoCloseable {
        final String name = "oa_gm_run_" + UUID.randomUUID().toString().replace("-", "");
        final PGSimpleDataSource owner;

        Db() throws SQLException {
            assertTrue(name.matches("oa_gm_run_[0-9a-f]{32}"));
            execute(data("postgres"), "create database " + name);
            owner = data(name);
            Flyway.configure().dataSource(owner).locations("classpath:db/migration").load().migrate();
        }

        Instant databaseNow() throws SQLException {
            try (var c = owner.getConnection(); var q = c.createStatement();
                    var r = q.executeQuery("select pg_catalog.clock_timestamp()")) {
                assertTrue(r.next());
                return r.getTimestamp(1).toInstant();
            }
        }

        @Override
        public void close() throws SQLException {
            assertTrue(name.matches("oa_gm_run_[0-9a-f]{32}"));
            execute(data("postgres"), "drop database " + name);
        }

        private static PGSimpleDataSource data(String database) {
            var ds = new PGSimpleDataSource();
            ds.setURL(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
            ds.setDatabaseName(database);
            ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
            ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres"));
            return ds;
        }

        private static void execute(DataSource data, String sql) throws SQLException {
            try (var c = data.getConnection(); var q = c.createStatement()) {
                q.setQueryTimeout(30);
                q.execute(sql);
            }
        }
    }
}
