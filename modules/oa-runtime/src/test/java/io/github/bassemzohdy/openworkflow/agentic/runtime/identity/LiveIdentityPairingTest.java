package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The credential-pairing overload of the identity half over the real guard, real grants and a private PostgreSQL database.
 * It covers no registry, lease, task, target, PDP, audit or Flow behaviour: none of that is part of this component.
 */
class LiveIdentityPairingTest {

    private static final String AUDIENCE = "oa-agent-run";
    private static final String ISSUER = "https://idp.test";
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject WORKFLOW = new Subject(SubjectKind.WORKFLOW, "research-workflow");

    private static String dbName;
    private static String adminUrl;
    private static DataSource data;

    private final Map<String, VerifiedToken> tokens = new ConcurrentHashMap<>();
    private MovableClock clock;
    private String tenant;
    private DelegationGrants grants;
    private DelegationGrant grant;
    private InMemoryIssuedTokenBindings issuedStore;
    private ResourceCallFixtures.InMemory store;
    private Runnable duringCallLookup = () -> { };
    private IntConsumer duringGrantBindingLookup = n -> { };
    private final AtomicInteger grantBindingLookups = new AtomicInteger();
    private Supplier<Optional<ResourceCallBinding>> callOverride;
    private Supplier<Optional<IssuedTokenBindings.Binding>> grantBindingOverride;
    private boolean callStoreDown;
    private ResourceTokenGuard guard;
    private AgentResourceAdmission admission;
    private LiveIdentityCheck check;
    private VerifiedToken lastToken;
    private ResourceCallBinding lastBinding;

    @BeforeAll
    static void privateDatabase() throws Exception {
        String url = IdentityTestSupport.URL;
        String base = url.substring(0, url.lastIndexOf('/') + 1);
        adminUrl = base + "postgres";
        dbName = "oa_wp102_pair_" + UUID.randomUUID().toString().replace("-", "");
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
        Thread.interrupted();
        tokens.clear();
        duringCallLookup = () -> { };
        duringGrantBindingLookup = n -> { };
        grantBindingLookups.set(0);
        callOverride = null;
        grantBindingOverride = null;
        callStoreDown = false;
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
        grants = new DelegationGrants(new DelegationGrantStore(data),
                IdentityTestSupport.audit(new IdentityTestSupport.RecordingSink(), clock), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        tenant = IdentityTestSupport.newTenant();
        grant = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        issuedStore = new InMemoryIssuedTokenBindings();
        store = new ResourceCallFixtures.InMemory();
        IssuedTokenBindings issued = new IssuedTokenBindings() {
            @Override
            public void bind(String issuer, String tokenId, Binding binding) {
                issuedStore.bind(issuer, tokenId, binding);
            }

            @Override
            public Optional<Binding> find(String issuer, String tokenId) {
                Optional<Binding> found = issuedStore.find(issuer, tokenId);
                duringGrantBindingLookup.accept(grantBindingLookups.incrementAndGet());
                return grantBindingOverride != null ? grantBindingOverride.get() : found;
            }
        };
        InMemoryReplayCache cache = new InMemoryReplayCache(100);
        guard = new ResourceTokenGuard((bearer, audience) -> {
            VerifiedToken t = tokens.get(bearer);
            if (t == null) {
                throw new TokenRejectedException(Reason.SIGNATURE);
            }
            return t;
        }, AUDIENCE, cache::use, grants, issued, clock, 1);
        admission = admission(guard, issued);
        check = new LiveIdentityCheck(admission);
    }

    private AgentResourceAdmission admission(ResourceTokenGuard g, IssuedTokenBindings issued) {
        ResourceCallBindings slow = new ResourceCallBindings() {
            @Override
            public ResourceCallBinding bind(ResourceCallBinding binding) {
                throw new AssertionError("the identity check must never write a binding");
            }

            @Override
            public Optional<ResourceCallBinding> find(String issuer, String tokenId) {
                if (callStoreDown) {
                    throw new IllegalStateException("store down");
                }
                Optional<ResourceCallBinding> found = store.find(issuer, tokenId);
                duringCallLookup.run();
                return callOverride != null ? callOverride.get() : found;
            }
        };
        return new AgentResourceAdmission(g, slow, issued, AUDIENCE, clock);
    }

    private ResourceCallScope scope() {
        return ResourceCallFixtures.scope(tenant, ALICE, AUDIENCE, Pep.AGENT_RESEARCH_RUN,
                ResourceCallFixtures.TOPIC_REQUEST);
    }

    private String credential(ResourceCallScope scope, DelegationGrant issuing, List<String> actors) {
        Instant exp = clock.instant().plus(Duration.ofMinutes(5));
        VerifiedToken t = new VerifiedToken(ISSUER, ALICE, Set.of(AUDIENCE), Set.of(AgentResourceAdmission.SCOPE), tenant,
                clock.instant(), exp, UUID.randomUUID().toString(), actors);
        issuedStore.bind(ISSUER, t.tokenId(), new IssuedTokenBindings.Binding(issuing.id(), tenant, exp));
        lastBinding = ResourceCallFixtures.binding(t, issuing.id(), scope);
        store.bind(lastBinding);
        lastToken = t;
        String bearer = "bearer-" + t.tokenId();
        tokens.put(bearer, t);
        return bearer;
    }

    private BoundCall bound(ResourceCallScope scope) {
        return bound(scope, credential(scope, grant, List.of(WORKFLOW.id())));
    }

    private BoundCall bound(ResourceCallScope scope, String bearer) {
        return admission.bind(admission.admitCall(bearer), new ResourceExecutionEntry(scope, 1));
    }

    private static ResourceExecutionEntry entry(ResourceCallScope scope, long epoch) {
        return new ResourceExecutionEntry(scope, epoch);
    }

    private static Reason reason(org.junit.jupiter.api.function.Executable r) {
        return assertThrows(TokenRejectedException.class, r).reason();
    }

    private static final Subject OTHER = new Subject(SubjectKind.WORKFLOW, "other-workflow");

    @Test
    void theExactAdmittedRecordPassesAndRemainsRepeatable() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        ResourceCallBinding record = lastBinding;
        assertEquals(record.key(), bound.bindingKey());
        check.requireLive(bound, entry(scope, 1), WORKFLOW, record);
        check.requireLive(bound, entry(scope, 1), WORKFLOW, record);
        check.requireLive(bound, entry(scope, 1), WORKFLOW); // the identity-only overload stays compatible
    }

    @Test
    void twoCredentialsOfTheSameScopeAndEntryHaveDifferentFingerprintsAndCannotBeSwapped() {
        ResourceCallScope scope = scope();
        AdmittedCall first = admission.admitCall(credential(scope, grant, List.of(WORKFLOW.id())));
        ResourceCallBinding firstRecord = lastBinding;
        AdmittedCall second = admission.admitCall(credential(scope, grant, List.of(WORKFLOW.id())));
        ResourceCallBinding secondRecord = lastBinding;
        assertEquals(first.callKey(), second.callKey());
        assertNotEquals(first.bindingKey(), second.bindingKey());
        assertEquals(firstRecord.key(), first.bindingKey());
        assertEquals(secondRecord.key(), second.bindingKey());
        BoundCall a = admission.bind(first, entry(scope, 1));
        BoundCall b = admission.bind(second, entry(scope, 1));
        assertEquals(first.bindingKey(), a.bindingKey());

        check.requireLive(a, entry(scope, 1), WORKFLOW, firstRecord);
        check.requireLive(b, entry(scope, 1), WORKFLOW, secondRecord);
        // an equal entry alone does not pair: the record of the other credential is refused
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(a, entry(scope, 1), WORKFLOW, secondRecord));
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(b, entry(scope, 1), WORKFLOW, firstRecord));
    }

    @Test
    void aForgedRecordWithTheSameKeyButAnyOtherFieldRefuses() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        ResourceCallBinding real = lastBinding;
        DelegationGrant another = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        ResourceCallScope otherScope = ResourceCallFixtures.scope(tenant, ALICE, AUDIENCE, Pep.AGENT_RESEARCH_RUN,
                "{\"topic\":\"y\"}");
        List<ResourceCallBinding> forged = List.of(
                new ResourceCallBinding(real.issuer(), real.tokenId(), another.id(), real.scope(), real.issuedAt(),
                        real.expiresAt()),
                new ResourceCallBinding(real.issuer(), real.tokenId(), real.issuingGrantId(), real.scope(),
                        real.issuedAt().plusSeconds(1), real.expiresAt()),
                new ResourceCallBinding(real.issuer(), real.tokenId(), real.issuingGrantId(), real.scope(),
                        real.issuedAt(), real.expiresAt().minusSeconds(1)),
                new ResourceCallBinding(real.issuer(), real.tokenId(), real.issuingGrantId(), otherScope,
                        real.issuedAt(), real.expiresAt()));
        for (ResourceCallBinding candidate : forged) {
            assertEquals(real.key(), candidate.key(), "same fingerprint");
            assertThrows(WitnessRefusedException.class,
                    () -> check.requireLive(bound, entry(scope, 1), WORKFLOW, candidate));
        }
        check.requireLive(bound, entry(scope, 1), WORKFLOW, real);
    }

    @Test
    void aMissingCandidateRefusesBeforeAnyStoreIsRead() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        duringCallLookup = () -> {
            throw new AssertionError("a refused pairing must not read the stores");
        };
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), WORKFLOW, null));
    }

    @Test
    void theExistingIdentityRefusalsStillApplyWithAnExactRecord() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        ResourceCallBinding record = lastBinding;
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 2), WORKFLOW, record));
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), OTHER, record));
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), null, record));
        LiveIdentityCheck foreign = new LiveIdentityCheck(admission(guard, issuedStore));
        assertThrows(WitnessRefusedException.class,
                () -> foreign.requireLive(bound, entry(scope, 1), WORKFLOW, record));

        BoundCall closing = bound(scope);
        ResourceCallBinding closingRecord = lastBinding;
        closing.close();
        assertThrows(WitnessRefusedException.class,
                () -> check.requireLive(closing, entry(scope, 1), WORKFLOW, closingRecord));

        grants.revoke(grant.id(), ALICE, "revoked", null);
        assertEquals(Reason.GRANT_INACTIVE,
                reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW, record)));
    }

    @Test
    void revocationDuringTheLookupStillRefusesAfterAPassingPairing() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        ResourceCallBinding record = lastBinding;
        duringCallLookup = () -> grants.revoke(grant.id(), ALICE, "revoked during the lookup", null);
        assertEquals(Reason.GRANT_INACTIVE,
                reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW, record)));
    }

    @Test
    void theFingerprintStaysHistoricalDataAfterCloseAndRevivesNothing() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        ResourceCallBinding record = lastBinding;
        String key = bound.bindingKey();
        bound.close();
        assertEquals(key, bound.bindingKey());
        assertEquals(record.key(), key);
        assertThrows(WitnessRefusedException.class,
                () -> check.requireLive(bound, entry(scope, 1), WORKFLOW, record));
        assertFalse(bound.toString().contains(key));
    }
}
