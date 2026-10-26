package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
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
import java.util.function.Consumer;
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
 * The synchronous identity half of a live check over the real guard, real grants and a private PostgreSQL database.
 * It covers no registry, lease, task, target, PDP, audit or Flow behaviour: none of that is part of this component.
 */
class LiveIdentityCheckTest {

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
    private Consumer<Ulid> afterGrantRead = id -> { };
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
        dbName = "oa_wp102_live_" + UUID.randomUUID().toString().replace("-", "");
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
        afterGrantRead = id -> { };
        duringGrantBindingLookup = n -> { };
        grantBindingLookups.set(0);
        callOverride = null;
        grantBindingOverride = null;
        callStoreDown = false;
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
        grants = new DelegationGrants(new DelegationGrantStore(data) {
            @Override
            public Optional<DelegationGrant> find(Ulid id) {
                Optional<DelegationGrant> found = super.find(id); // a real row, read before the delay
                afterGrantRead.accept(id);
                return found;
            }
        },
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

    @Test
    void aLiveRealCredentialForTheExpectedWorkloadPassesAndNothingIsConsumedOrWritten() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        check.requireLive(bound, entry(scope, 1), WORKFLOW);
        check.requireLive(bound, entry(scope, 1), WORKFLOW); // repeatable: nothing was consumed or cached
    }

    @Test
    void revocationDuringTheSlowBindingLookupRefusesAfterTheLookup() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        duringCallLookup = () -> grants.revoke(grant.id(), ALICE, "revoked during the lookup", null);
        assertEquals(Reason.GRANT_INACTIVE, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));
        // retained read data is still readable and is still not permission
        assertEquals(scope, bound.entry().scope());
        duringCallLookup = () -> { };
        assertEquals(Reason.GRANT_INACTIVE, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));
    }

    @Test
    void signedExpiryDuringTheSlowBindingLookupRefusesAfterTheLookup() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        duringCallLookup = () -> clock.advance(Duration.ofMinutes(6));
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), WORKFLOW));
    }

    @Test
    void signedExpiryDuringTheGrantBindingWorkRefusesAfterIt() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        grantBindingLookups.set(0);
        // lookup 1 is the check own re-read; lookup 2 is the guard grant check, which then blocks past the expiry
        duringGrantBindingLookup = n -> {
            if (n == 2) {
                clock.advance(Duration.ofMinutes(6));
            }
        };
        assertEquals(Reason.EXPIRED, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));
    }

    @Test
    void revokedAncestorRefuses() {
        Subject child = new Subject(SubjectKind.AGENT, "child-agent");
        DelegationGrant delegated = grants.delegate(grant.id(), WORKFLOW, child, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofMinutes(30)), null);
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope, credential(scope, delegated, List.of(child.id(), WORKFLOW.id())));
        check.requireLive(bound, entry(scope, 1), child);
        grants.revoke(grant.id(), ALICE, "ancestor revoked", null);
        assertEquals(Reason.GRANT_INACTIVE, reason(() -> check.requireLive(bound, entry(scope, 1), child)));
    }

    @Test
    void missingRetargetedOrMismatchedBindingsRefuse() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        ResourceCallBinding real = lastBinding;

        callOverride = Optional::empty;
        assertEquals(Reason.BINDING_MISSING, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));

        ResourceCallScope other = ResourceCallFixtures.scope(tenant, ALICE, AUDIENCE, Pep.AGENT_RESEARCH_RUN,
                "{\"topic\":\"y\"}");
        callOverride = () -> Optional.of(new ResourceCallBinding(real.issuer(), real.tokenId(), real.issuingGrantId(),
                other, real.issuedAt(), real.expiresAt()));
        assertEquals(Reason.BINDING_MISMATCH, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));

        DelegationGrant another = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofHours(1)), null);
        callOverride = () -> Optional.of(new ResourceCallBinding(real.issuer(), real.tokenId(), another.id(),
                real.scope(), real.issuedAt(), real.expiresAt()));
        assertEquals(Reason.BINDING_MISMATCH, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));

        callOverride = () -> Optional.of(new ResourceCallBinding(real.issuer(), real.tokenId(), real.issuingGrantId(),
                real.scope(), real.issuedAt(), real.expiresAt().minusSeconds(1)));
        assertEquals(Reason.BINDING_MISMATCH, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));

        // a later issued time keeps the lifetime valid, so only the equality with the admitted binding can refuse it
        callOverride = () -> Optional.of(new ResourceCallBinding(real.issuer(), real.tokenId(), real.issuingGrantId(),
                real.scope(), real.issuedAt().plusSeconds(1), real.expiresAt()));
        assertEquals(Reason.BINDING_MISMATCH, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));

        callOverride = null;
        grantBindingOverride = () -> Optional.of(new IssuedTokenBindings.Binding(another.id(), tenant,
                lastToken.expiresAt()));
        assertEquals(Reason.BINDING_MISMATCH, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));
        grantBindingOverride = Optional::empty;
        assertEquals(Reason.BINDING_MISMATCH, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));

        grantBindingOverride = null;
        check.requireLive(bound, entry(scope, 1), WORKFLOW); // every refusal above was about the lookups, not the call
    }

    @Test
    void anUnavailableBindingStoreRefuses() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        callStoreDown = true;
        assertEquals(Reason.VERIFIER_UNAVAILABLE, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));
    }

    @Test
    void theActingWorkloadMustBeTheIssuingGrantTypedAgent() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        for (Subject wrong : List.of(new Subject(SubjectKind.AGENT, WORKFLOW.id()), // same id, wrong kind
                new Subject(SubjectKind.SERVICE, WORKFLOW.id()), new Subject(SubjectKind.WORKFLOW, "someone-else"),
                new Subject(SubjectKind.USER, WORKFLOW.id()), ALICE)) {
            assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), wrong),
                    wrong.toString());
        }
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), null));
        check.requireLive(bound, entry(scope, 1), WORKFLOW);
    }

    @Test
    void wrongEntryEpochMinterOrClosedHandlesRefuse() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 2), WORKFLOW));
        ResourceCallScope changed = ResourceCallFixtures.scope(tenant, ALICE, AUDIENCE, Pep.AGENT_RESEARCH_RUN,
                "{\"topic\":\"y\"}");
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(changed, 1), WORKFLOW));
        LiveIdentityCheck foreign = new LiveIdentityCheck(admission(guard, issuedStore));
        assertThrows(WitnessRefusedException.class, () -> foreign.requireLive(bound, entry(scope, 1), WORKFLOW));

        BoundCall perCall = bound(scope);
        BoundCall sibling = bound(scope);
        perCall.close();
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(perCall, entry(scope, 1), WORKFLOW));
        check.requireLive(sibling, entry(scope, 1), WORKFLOW);

        admission.close();
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(sibling, entry(scope, 1), WORKFLOW));
    }

    @Test
    void closeDuringTheSlowLookupRefuses() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        duringCallLookup = admission::close;
        assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), WORKFLOW));
    }

    @Test
    void anInterruptRefusesAndTheFlagIsPreserved() {
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope);
        Thread.currentThread().interrupt();
        try {
            assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), WORKFLOW));
            assertTrue(Thread.currentThread().isInterrupted(), "the flag is preserved");
        } finally {
            Thread.interrupted();
        }
        duringCallLookup = () -> Thread.currentThread().interrupt();
        try {
            assertThrows(WitnessRefusedException.class, () -> check.requireLive(bound, entry(scope, 1), WORKFLOW));
            assertTrue(Thread.currentThread().isInterrupted(), "an interrupt during the lookup is preserved");
        } finally {
            Thread.interrupted();
        }
    }

    // ---- grant expiry during the blocking lineage reads -----------------------------------------------------------------

    /** Runs {@code delay} once, after the grant {@code id} has been read; later reads are undelayed. */
    private void delayAfterReadOf(Ulid id, Runnable delay) {
        AtomicInteger fired = new AtomicInteger();
        afterGrantRead = read -> {
            if (read.equals(id) && fired.getAndIncrement() == 0) {
                delay.run();
            }
        };
    }

    @Test
    void aGrantShorterThanItsTokenThatExpiresDuringItsReadRefuses() {
        DelegationGrant shortGrant = grants.grant(tenant, ALICE, ALICE, WORKFLOW, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofMinutes(2)), null);
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope, credential(scope, shortGrant, List.of(WORKFLOW.id())));
        // the signed token (5 minutes) outlives the grant (2 minutes); only the grant check can see it
        delayAfterReadOf(shortGrant.id(), () -> clock.advance(Duration.ofMinutes(3)));
        assertEquals(Reason.GRANT_INACTIVE, reason(() -> check.requireLive(bound, entry(scope, 1), WORKFLOW)));
        assertTrue(lastToken.expiresAt().isAfter(clock.instant()), "the token itself is still inside its signed time");
    }

    @Test
    void aChildThatExpiresExactlyWhileTheAncestorIsReadRefuses() {
        Subject child = new Subject(SubjectKind.AGENT, "child-agent");
        DelegationGrant delegated = grants.delegate(grant.id(), WORKFLOW, child, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofMinutes(2)), null);
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope, credential(scope, delegated, List.of(child.id(), WORKFLOW.id())));
        // the child is read live; the (much longer) ancestor read then takes exactly until the child's expiry
        delayAfterReadOf(grant.id(), () -> clock.advance(Duration.ofMinutes(2)));
        assertEquals(Reason.GRANT_INACTIVE, reason(() -> check.requireLive(bound, entry(scope, 1), child)));
    }

    @Test
    void anUnchangedActiveChainStillPassesOneMicrosecondBeforeExpiry() {
        Subject child = new Subject(SubjectKind.AGENT, "child-agent");
        DelegationGrant delegated = grants.delegate(grant.id(), WORKFLOW, child, Set.of(AgentResourceAdmission.SCOPE),
                clock.instant().plus(Duration.ofMinutes(2)), null);
        ResourceCallScope scope = scope();
        BoundCall bound = bound(scope, credential(scope, delegated, List.of(child.id(), WORKFLOW.id())));
        delayAfterReadOf(grant.id(), () -> clock.advance(Duration.ofMinutes(2).minusNanos(1_000)));
        check.requireLive(bound, entry(scope, 1), child);
    }
}
