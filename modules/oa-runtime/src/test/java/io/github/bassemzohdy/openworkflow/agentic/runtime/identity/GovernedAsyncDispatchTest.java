package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException.Reason;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Decision;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResumeGate.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernedAsyncDispatchTest {
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Subject USER = new Subject(SubjectKind.USER, "alice");
    private static final Subject AGENT = new Subject(SubjectKind.AGENT, "researcher");

    private final Ulid instanceId = UlidGenerator.shared().next();
    private final Ulid grantId = UlidGenerator.shared().next();
    private final InstanceAuthority authority = new InstanceAuthority(instanceId, "t1", USER, grantId, "corr-1",
            InstanceAuthority.State.AUTHORIZED, null, NOW, NOW);
    private final DelegationGrant grant = new DelegationGrant(grantId, "t1", USER, AGENT, Set.of("cases:read"), null,
            List.of("researcher"), DelegationGrant.Status.ACTIVE, NOW, USER, NOW.plus(Duration.ofHours(1)), null, null, 1);

    private final AtomicReference<Clock> clock = new AtomicReference<>(Clock.fixed(NOW, ZoneOffset.UTC));
    private Optional<InstanceAuthority> registered;
    private Decision decision;
    private RuntimeException registryFailure;
    private RuntimeException auditFailure;
    private Optional<Credential> subject;
    private final List<String> marked = new ArrayList<>();
    private final List<ExchangeRequest> requests = new ArrayList<>();
    private TokenExchanger exchanger;
    private GovernedAsyncDispatch dispatch;

    @BeforeEach void setUp() {
        registered = Optional.of(authority);
        decision = Decision.proceed();
        subject = Optional.of(Credential.of("user-token"));
        exchanger = request -> {
            requests.add(request);
            return new IssuedToken(Credential.of("fresh-" + requests.size()), request.audience(), request.scopes(),
                    NOW.plusSeconds(300), "issuer", "jti-" + requests.size(), List.of("researcher"), grantId);
        };
        dispatch = build();
    }

    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    private GovernedAsyncDispatch build() {
        var registry = new GovernedAsyncDispatch.Registry() {
            @Override public Optional<InstanceAuthority> find(String id) {
                if (registryFailure != null) throw registryFailure;
                return registered;
            }
            @Override public Decision recheck(Ulid id) { return decision; }
            @Override public void requireReauthorization(InstanceAuthority a, String reason) {
                if (auditFailure != null) throw auditFailure;
                marked.add(reason);
            }
            @Override public Optional<DelegationGrant> grant(Ulid id) { return Optional.of(grant); }
        };
        var credentials = new GovernedAsyncDispatch.Credentials() {
            @Override public Optional<Credential> subject(InstanceAuthority a) { return subject; }
            @Override public Credential actor() { return Credential.of("workload-token"); }
        };
        return new GovernedAsyncDispatch(registry, credentials, request -> exchanger.exchange(request), new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return clock.get().instant(); }
        });
    }

    private String run() {
        return dispatch.run("engine-1", "tool-cases", Set.of("cases:read"), token -> "done:" + token.audience());
    }

    private void assertLost(Reason reason, Runnable action) {
        assertEquals(reason, assertThrows(AuthorityLostException.class, action::run).reason());
    }

    @Test void aLiveTaskGetsAFreshTokenForExactlyOneAudienceAndTheInstanceScope() {
        var seenScope = new AtomicReference<InstanceAuthority>();
        var result = dispatch.run("engine-1", "tool-cases", Set.of("cases:read"), token -> {
            seenScope.set(AuthorityScope.current().orElseThrow());
            return token.audience();
        });
        assertEquals("tool-cases", result);
        assertEquals(authority, seenScope.get(), "work runs under the registry's subject, grant and correlation");
        assertTrue(AuthorityScope.current().isEmpty(), "the scope is closed afterwards");
        assertEquals(1, requests.size());
        assertEquals(Set.of("cases:read"), requests.get(0).scopes());
        assertEquals(grant, requests.get(0).grant());
    }

    @Test void everyTaskExchangesAgainAndNeverReusesATokenAfterAWait() {
        run();
        clock.set(Clock.fixed(NOW.plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
        exchanger = request -> {
            requests.add(request);
            return new IssuedToken(Credential.of("fresh-later"), request.audience(), request.scopes(),
                    clock.get().instant().plusSeconds(300), "issuer", "jti-later", List.of("researcher"), grantId);
        };
        run();
        assertEquals(2, requests.size(), "a second task is a second exchange");
    }

    @Test void anUnknownUndelegatedOrUnreadableInstanceNeverRunsWithoutIdentity() {
        var ran = new AtomicReference<>(false);
        registered = Optional.empty();
        assertLost(Reason.NOT_VERIFIED, () -> dispatch.run("engine-1", "tool-cases", Set.of("cases:read"),
                t -> ran.getAndSet(true)));
        registered = Optional.of(new InstanceAuthority(instanceId, "t1", USER, null, "corr-1",
                InstanceAuthority.State.AUTHORIZED, null, NOW, NOW));
        assertLost(Reason.NOT_VERIFIED, this::run);
        registryFailure = new IllegalStateException("db down");
        assertLost(Reason.UNAVAILABLE, this::run);
        registryFailure = null;
        assertLost(Reason.NOT_VERIFIED, () -> dispatch.run(" ", "tool-cases", Set.of("cases:read"), t -> "x"));
        assertFalse(ran.get());
        assertTrue(requests.isEmpty(), "nothing was exchanged");
    }

    @Test void aRevokedGrantOrAHeldInstanceRefusesBeforeAnyExchange() {
        decision = new Decision(Outcome.REAUTHORIZATION_REQUIRED, "grant.revoked");
        assertLost(Reason.REVOKED, this::run);
        decision = new Decision(Outcome.UNAVAILABLE, "authority.unavailable");
        assertLost(Reason.UNAVAILABLE, this::run);
        assertTrue(requests.isEmpty());
    }

    @Test void noCurrentSubjectCredentialMeansReauthorizationNotAnAmbientIdentity() {
        subject = Optional.empty();
        assertLost(Reason.EXPIRED, this::run);
        assertTrue(requests.isEmpty());
    }

    @Test void anInactiveGrantAtExchangeMarksTheInstanceAndRefuses() {
        exchanger = request -> { throw new ExchangeException(ExchangeException.Code.GRANT_INACTIVE, "inactive"); };
        assertLost(Reason.REVOKED, this::run);
        assertEquals(List.of("grant.inactive"), marked);
    }

    @Test void aFailedAuditWriteStillRefusesTheStep() {
        exchanger = request -> { throw new ExchangeException(ExchangeException.Code.GRANT_INACTIVE, "inactive"); };
        auditFailure = new IllegalStateException("audit store down");
        assertLost(Reason.REVOKED, this::run);
        assertTrue(marked.isEmpty(), "nothing was marked without its audit record");
    }

    @Test void aRefusedOrUnavailableExchangeAndAnExpiredTokenRefuse() {
        exchanger = request -> { throw new ExchangeException(ExchangeException.Code.UNAVAILABLE, "down"); };
        assertLost(Reason.UNAVAILABLE, this::run);
        exchanger = request -> { throw new IllegalStateException("boom"); };
        assertLost(Reason.UNAVAILABLE, this::run);
        exchanger = request -> new IssuedToken(Credential.of("old"), request.audience(), request.scopes(),
                NOW.minusSeconds(1), "issuer", "jti", List.of("researcher"), grantId);
        assertLost(Reason.EXPIRED, this::run);
        assertTrue(marked.isEmpty(), "only an inactive grant marks the instance");
    }

    @Test void revocationDuringBlockingWorkWithholdsTheResult() {
        var released = new ArrayList<String>();
        assertLost(Reason.REVOKED, () -> released.add(dispatch.run("engine-1", "tool-cases", Set.of("cases:read"), t -> {
            decision = new Decision(Outcome.REAUTHORIZATION_REQUIRED, "grant.revoked");
            return "output";
        })));
        assertTrue(released.isEmpty(), "the output of work that outlived its grant is never released");
    }

    @Test void theTokenExpiringDuringBlockingWorkWithholdsTheResult() {
        assertLost(Reason.EXPIRED, () -> dispatch.run("engine-1", "tool-cases", Set.of("cases:read"), t -> {
            clock.set(Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC));
            return "output";
        }));
    }

    @Test void aWorkFailureIsNotAnAuthorityFailureAndTheScopeStillCloses() {
        var failure = new IllegalArgumentException("business error");
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> dispatch.run("engine-1", "tool-cases", Set.of("cases:read"), t -> { throw failure; })));
        assertTrue(AuthorityScope.current().isEmpty());
    }

    @Test void anInterruptedThreadRefusesAndStaysInterrupted() {
        Thread.currentThread().interrupt();
        assertLost(Reason.UNAVAILABLE, this::run);
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(requests.isEmpty());
    }

    @Test void aMalformedAudienceOrEmptyScopesNeverReachTheExchanger() {
        assertLost(Reason.UNAVAILABLE, () -> dispatch.run("engine-1", "bad audience!", Set.of("cases:read"), t -> "x"));
        assertLost(Reason.UNAVAILABLE, () -> dispatch.run("engine-1", "tool-cases", Set.of(), t -> "x"));
        assertTrue(requests.isEmpty());
    }

    @Test void theBearerIsNeverPartOfTheScopeOrAnyDescription() {
        dispatch.run("engine-1", "tool-cases", Set.of("cases:read"), token -> {
            assertFalse(AuthorityScope.current().orElseThrow().toString().contains("fresh-"));
            assertFalse(token.toString().contains("fresh-"), "IssuedToken printing redacts the bearer");
            return null;
        });
    }
}
