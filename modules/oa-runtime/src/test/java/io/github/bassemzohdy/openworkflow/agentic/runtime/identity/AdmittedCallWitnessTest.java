package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AgentResourceAdmission.AdmittedCall;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AgentResourceAdmission.BoundCall;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AgentResourceAdmission.WitnessRefusedException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.TokenRejectedException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The opaque admission witness: minted only by a real successful verification plus full call-binding lookup, bound once
 * to an exact backend-owned entry. Everything goes through {@code AgentResourceAdmission.admitCall}; no live Flow,
 * graph, worker or Flow serialization/propagation behaviour is covered (none is implemented). Uses a private database.
 */
class AdmittedCallWitnessTest {

    private static final String AUDIENCE = "oa-agent-run";
    private static final String ISSUER = "https://idp.test";
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "research-workflow");

    private static String dbName;
    private static String adminUrl;
    private static DataSource data;

    private final Map<String, VerifiedToken> tokens = new ConcurrentHashMap<>();
    private final AtomicInteger replayUses = new AtomicInteger();
    private final AtomicInteger bindingWrites = new AtomicInteger();
    private MovableClock clock;
    private String tenant;
    private DelegationGrants grants;
    private DelegationGrant grant;
    private InMemoryIssuedTokenBindings issued;
    private ResourceCallFixtures.InMemory store;
    private Runnable duringLookup = () -> { };
    private ResourceTokenGuard guard;
    private AgentResourceAdmission admission;

    @BeforeAll
    static void privateDatabase() throws Exception {
        String url = IdentityTestSupport.URL;
        String base = url.substring(0, url.lastIndexOf('/') + 1);
        adminUrl = base + "postgres";
        dbName = "oa_wp102_witness_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(adminUrl, IdentityTestSupport.USER, IdentityTestSupport.PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        String own = base + dbName;
        Flyway.configure().dataSource(own, IdentityTestSupport.USER, IdentityTestSupport.PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(own);
        ds.setUser(IdentityTestSupport.USER);
        ds.setPassword(IdentityTestSupport.PASSWORD);
        data = ds;
    }

    @AfterAll
    static void dropPrivateDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(adminUrl, IdentityTestSupport.USER, IdentityTestSupport.PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
        }
    }

    @BeforeEach
    void setUp() {
        tokens.clear();
        replayUses.set(0);
        bindingWrites.set(0);
        duringLookup = () -> { };
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
        grants = new DelegationGrants(new DelegationGrantStore(data),
                IdentityTestSupport.audit(new IdentityTestSupport.RecordingSink(), clock), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        tenant = IdentityTestSupport.newTenant();
        grant = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        issued = new InMemoryIssuedTokenBindings();
        store = new ResourceCallFixtures.InMemory();
        InMemoryReplayCache cache = new InMemoryReplayCache(100);
        ReplayCache counting = (key, expiresAt, maxUses, now) -> {
            boolean allowed = cache.use(key, expiresAt, maxUses, now);
            if (allowed) {
                replayUses.incrementAndGet();
            }
            return allowed;
        };
        guard = new ResourceTokenGuard((bearer, audience) -> {
            VerifiedToken t = tokens.get(bearer);
            if (t == null) {
                throw new TokenRejectedException(Reason.SIGNATURE);
            }
            return t;
        }, AUDIENCE, counting, grants, issued, clock, 1);
        admission = admission(guard);
    }

    private AgentResourceAdmission admission(ResourceTokenGuard g) {
        ResourceCallBindings slow = new ResourceCallBindings() {
            @Override
            public ResourceCallBinding bind(ResourceCallBinding binding) {
                bindingWrites.incrementAndGet();
                throw new AssertionError("admission must never write a binding");
            }

            @Override
            public Optional<ResourceCallBinding> find(String issuer, String tokenId) {
                Optional<ResourceCallBinding> found = store.find(issuer, tokenId);
                duringLookup.run();
                return found;
            }
        };
        return new AgentResourceAdmission(g, slow, issued, AUDIENCE, clock);
    }

    private ResourceCallScope scope() {
        return ResourceCallFixtures.scope(tenant, ALICE, AUDIENCE, Pep.AGENT_RESEARCH_RUN,
                ResourceCallFixtures.TOPIC_REQUEST);
    }

    /** Issues a verified delegated credential with its grant binding and (when scope != null) its call binding. */
    private String credential(ResourceCallScope scope) {
        Instant exp = clock.instant().plus(Duration.ofMinutes(5));
        VerifiedToken t = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of(AgentResourceAdmission.SCOPE), tenant,
                clock.instant(), exp, UUID.randomUUID().toString(), List.of(WORKFLOW.id()));
        issued.bind(ISSUER, t.tokenId(), new IssuedTokenBindings.Binding(grant.id(), tenant, exp));
        if (scope != null) {
            store.bind(ResourceCallFixtures.binding(t, grant.id(), scope));
        }
        String bearer = "bearer-" + t.tokenId();
        tokens.put(bearer, t);
        return bearer;
    }

    private static ResourceCallScope variant(ResourceCallScope s, String what) {
        return new ResourceCallScope(s.tenant(), s.instanceId(), s.owner(), s.applicationId(), s.engineInstanceId(),
                s.namespace(), s.definition(), s.version(), s.taskPosition(), s.iteration(), s.logicalOccurrence(),
                what.equals("audience") ? "other-audience" : s.audience(),
                what.equals("operation") ? "agent:other:run" : s.operation(),
                what.equals("target") ? "b".repeat(64) : s.targetFingerprint(),
                what.equals("request") ? ResourceCallScope.requestFingerprint("{\"topic\":\"y\"}")
                        : s.requestFingerprint());
    }

    private static ResourceExecutionEntry entry(ResourceCallScope scope, long epoch) {
        return new ResourceExecutionEntry(scope, epoch);
    }

    @Test
    void realVerifiedAdmissionMintsAWitnessThatBindsOnceToTheExactEntry() {
        ResourceCallScope scope = scope();
        AdmittedCall call = admission.admitCall(credential(scope));
        assertEquals(scope.key(), call.callKey());
        assertEquals(scope, call.scope());
        ResourceExecutionEntry entry = entry(scope, 1);
        BoundCall bound = admission.bind(call, entry);
        assertSame(entry, bound.entry());
        assertSame(entry, bound.requireEntry(entry(scope, 1)));
        assertEquals(1, replayUses.get(), "admission consumed the single use once; binding did not repeat it");
        assertEquals(0, bindingWrites.get());
    }

    @Test
    void badMissingOrExpiredInputsCannotYieldAWitness() {
        assertEquals(Reason.SIGNATURE,
                assertThrows(TokenRejectedException.class, () -> admission.admitCall("forged")).reason());
        String unbound = credential(null);
        assertEquals(Reason.BINDING_MISSING,
                assertThrows(TokenRejectedException.class, () -> admission.admitCall(unbound)).reason());
        String late = credential(scope());
        clock.advance(Duration.ofMinutes(6));
        assertEquals(Reason.EXPIRED,
                assertThrows(TokenRejectedException.class, () -> admission.admitCall(late)).reason());
        assertEquals(Reason.AUDIENCE, assertThrows(TokenRejectedException.class,
                () -> new AgentResourceAdmission(null, store, issued, AUDIENCE, clock).admitCall("x")).reason());
    }

    @Test
    void constructibleDataIsNotAMintPath() {
        // forged data can be built, but admission only trusts a bearer it verifies itself
        VerifiedToken forged = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of(AgentResourceAdmission.SCOPE),
                tenant, clock.instant(), clock.instant().plusSeconds(60), "forged", List.of(WORKFLOW.id()));
        assertEquals("forged", forged.tokenId());
        assertThrows(TokenRejectedException.class, () -> admission.admitCall("forged"));
    }

    @Test
    void aWitnessIsNotBoundByAnotherAdmissionInstance() {
        AdmittedCall call = admission.admitCall(credential(scope()));
        AgentResourceAdmission other = admission(guard);
        assertThrows(WitnessRefusedException.class, () -> other.bind(call, entry(call.scope(), 1)));
        admission.bind(call, entry(call.scope(), 1)); // the foreign attempt did not burn the witness
    }

    @Test
    void closeInvalidatesPromptly() {
        ResourceCallScope scope = scope();
        AdmittedCall unbound = admission.admitCall(credential(scope));
        AdmittedCall other = admission.admitCall(credential(scope));
        BoundCall bound = admission.bind(other, entry(scope, 1));
        admission.close();
        assertThrows(WitnessRefusedException.class, () -> admission.bind(unbound, entry(scope, 1)));
        assertThrows(WitnessRefusedException.class, () -> bound.requireEntry(entry(scope, 1)));
        String next = credential(scope);
        assertThrows(WitnessRefusedException.class, () -> admission.admitCall(next));
    }

    @Test
    void closingOneCallDoesNotCloseAnotherFromTheSameAdmission() {
        ResourceCallScope scope = scope();
        AdmittedCall a = admission.admitCall(credential(scope));
        AdmittedCall b = admission.admitCall(credential(scope));
        BoundCall boundA = admission.bind(a, entry(scope, 1));
        BoundCall boundB = admission.bind(b, entry(scope, 1));
        boundA.close();
        assertThrows(WitnessRefusedException.class, () -> boundA.requireEntry(entry(scope, 1)));
        assertEquals(entry(scope, 1), boundB.requireEntry(entry(scope, 1)));
        AdmittedCall c = admission.admitCall(credential(scope));
        admission.bind(c, entry(scope, 1)); // the admission itself is still open
    }

    @Test
    void aClosedUnboundWitnessRefusesBindAndStaysClosed() {
        ResourceCallScope scope = scope();
        AdmittedCall a = admission.admitCall(credential(scope));
        AdmittedCall b = admission.admitCall(credential(scope));
        a.close();
        a.close(); // idempotent
        assertThrows(WitnessRefusedException.class, () -> admission.bind(a, entry(scope, 1)));
        admission.bind(b, entry(scope, 1));
    }

    @Test
    void closingTheOriginatingWitnessInvalidatesItsBoundCall() {
        ResourceCallScope scope = scope();
        AdmittedCall a = admission.admitCall(credential(scope));
        BoundCall bound = admission.bind(a, entry(scope, 1));
        assertEquals(entry(scope, 1), bound.requireEntry(entry(scope, 1)));
        a.close();
        assertThrows(WitnessRefusedException.class, () -> bound.requireEntry(entry(scope, 1)));
        assertEquals(1, bound.entry().epoch()); // historical data accessor stays data-only
        assertEquals(scope, a.scope());
    }

    @Test
    void concurrentCloseAndBindNeverLeaveAUsableClosedWitness() throws Exception {
        ResourceCallScope scope = scope();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 50; i++) {
                AdmittedCall call = admission.admitCall(credential(scope));
                CountDownLatch go = new CountDownLatch(1);
                Future<BoundCall> binder = pool.submit(() -> {
                    go.await();
                    return admission.bind(call, entry(scope, 1));
                });
                Future<?> closer = pool.submit(() -> {
                    go.await();
                    call.close();
                    return null;
                });
                go.countDown();
                closer.get();
                try {
                    BoundCall bound = binder.get();
                    assertThrows(WitnessRefusedException.class, () -> bound.requireEntry(entry(scope, 1)));
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof WitnessRefusedException);
                }
                assertThrows(WitnessRefusedException.class, () -> admission.bind(call, entry(scope, 1)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closeDuringTheSlowBindingLookupRefusesTheNewWitness() {
        String bearer = credential(scope());
        duringLookup = admission::close;
        assertThrows(WitnessRefusedException.class, () -> admission.admitCall(bearer));
    }

    @Test
    void expiredCredentialRefusesBindAndLaterChecks() {
        ResourceCallScope scope = scope();
        AdmittedCall call = admission.admitCall(credential(scope));
        BoundCall bound = admission.bind(admission.admitCall(credential(scope)), entry(scope, 1));
        clock.advance(Duration.ofMinutes(6));
        assertThrows(WitnessRefusedException.class, () -> admission.bind(call, entry(scope, 1)));
        assertThrows(WitnessRefusedException.class, () -> bound.requireEntry(entry(scope, 1)));
    }

    @Test
    void aFullScopeChangeRefusesEvenWithTheSameCallKey() {
        ResourceCallScope scope = scope();
        for (String what : List.of("target", "request", "audience", "operation")) {
            ResourceCallScope changed = variant(scope, what);
            assertEquals(scope.key(), changed.key(), what + " keeps the call key");
            assertNotEquals(scope, changed);
            AdmittedCall call = admission.admitCall(credential(scope));
            assertThrows(WitnessRefusedException.class, () -> admission.bind(call, entry(changed, 1)), what);
            admission.bind(call, entry(scope, 1)); // the refusal did not consume the once-only bind
        }
    }

    @Test
    void aSecondBindOrAChangedRetainedEpochRefuses() {
        ResourceCallScope scope = scope();
        AdmittedCall call = admission.admitCall(credential(scope));
        BoundCall bound = admission.bind(call, entry(scope, 1));
        assertThrows(WitnessRefusedException.class, () -> admission.bind(call, entry(scope, 1)));
        assertThrows(WitnessRefusedException.class, () -> admission.bind(call, entry(scope, 2)));
        assertThrows(WitnessRefusedException.class, () -> bound.requireEntry(entry(scope, 2)));
        assertThrows(WitnessRefusedException.class,
                () -> bound.requireEntry(entry(variant(scope, "request"), 1)));
        assertEquals(1, bound.entry().epoch());
    }

    @Test
    void concurrentBindsOfOneWitnessYieldExactlyOne() throws Exception {
        ResourceCallScope scope = scope();
        AdmittedCall call = admission.admitCall(credential(scope));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<BoundCall>> results = new ArrayList<>();
            for (int epoch = 1; epoch <= 8; epoch++) {
                ResourceExecutionEntry entry = entry(scope, epoch);
                results.add(pool.submit(() -> {
                    go.await();
                    return admission.bind(call, entry);
                }));
            }
            go.countDown();
            int won = 0;
            for (Future<BoundCall> f : results) {
                try {
                    f.get();
                    won++;
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof WitnessRefusedException);
                }
            }
            assertEquals(1, won);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSecondCredentialAttestsTheSameCallButIsNotAFreshEntryClaim() {
        ResourceCallScope scope = scope();
        AdmittedCall first = admission.admitCall(credential(scope));
        AdmittedCall second = admission.admitCall(credential(scope));
        assertEquals(first.callKey(), second.callKey());
        assertEquals(2, replayUses.get(), "each credential is its own single use");
        // both are attribution only; which one (if any) is the fresh entry is up to the durable gate
        BoundCall a = admission.bind(first, entry(scope, 1));
        BoundCall b = admission.bind(second, entry(scope, 1));
        assertEquals(a.entry(), b.entry());
    }

    @Test
    void revocationOrExpiryInsideTheSlowLookupRefusesAndReplayIsNotConsumedTwice() {
        String revoked = credential(scope());
        duringLookup = () -> grants.revoke(grant.id(), ALICE, "revoked during the lookup", null);
        assertEquals(Reason.GRANT_INACTIVE,
                assertThrows(TokenRejectedException.class, () -> admission.admitCall(revoked)).reason());
        assertEquals(1, replayUses.get(), "the use was consumed once; the recheck did not consume again");

        setUp();
        String slow = credential(scope());
        duringLookup = () -> clock.advance(Duration.ofMinutes(6));
        assertEquals(Reason.EXPIRED,
                assertThrows(TokenRejectedException.class, () -> admission.admitCall(slow)).reason());
        assertEquals(1, replayUses.get());
        duringLookup = () -> { };
        assertEquals(Reason.EXPIRED,
                assertThrows(TokenRejectedException.class, () -> admission.admitCall(slow)).reason());
        assertEquals(1, replayUses.get());
    }

    @Test
    void aRepeatedBearerIsReplayedAndTheHistoricalAdmitStillWorks() {
        ResourceCallScope scope = scope();
        String bearer = credential(scope);
        AdmittedCall call = admission.admitCall(bearer);
        assertEquals(Reason.REPLAYED,
                assertThrows(TokenRejectedException.class, () -> admission.admitCall(bearer)).reason());
        assertEquals(scope, call.scope());
        assertEquals(scope, admission.admit(credential(scope)).call().scope());
    }

    @Test
    void handlesAreNotSerializableAndTheirTextCarriesNothing() throws Exception {
        ResourceCallScope scope = scope();
        AdmittedCall call = admission.admitCall(credential(scope));
        BoundCall bound = admission.bind(call, entry(scope, 1));
        for (Object handle : new Object[] {call, bound}) {
            assertThrows(NotSerializableException.class,
                    () -> new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(handle));
            String json = null;
            try {
                json = new ObjectMapper().writeValueAsString(handle);
            } catch (JsonProcessingException refused) {
                // expected: no serializable properties
            }
            assertNull(json, "Jackson must not serialize a handle");
            String text = handle.toString();
            assertFalse(text.contains("alice") || text.contains(tenant) || text.contains(scope.key()), text);
        }
    }
}
