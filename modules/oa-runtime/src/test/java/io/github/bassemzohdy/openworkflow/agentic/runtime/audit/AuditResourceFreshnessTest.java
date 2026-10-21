package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContextResolver;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WP-103, GPT review of #50: the audit API judges a verified caller's signed expiry again after policy, mandatory audit
 * and parsing, immediately before any query or verification I/O (P1), and a policy engine that cannot even be built is
 * the same 503 refusal as an unavailable decision (P2). The classes are the real resolver, resource, access and PEP;
 * only the verifier, the audit sink and the data source are doubles.
 */
class AuditResourceFreshnessTest {

    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");

    /** A clock the test can move. */
    private static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T10:00:00Z"));

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
        }

        @Override public Instant instant() {
            return now.get();
        }

        @Override public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private TestClock clock;
    private AtomicInteger connections;
    private Runnable duringAudit;
    private AuditResource resource;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        connections = new AtomicInteger();
        duringAudit = () -> { };
        VerifiedToken token = new VerifiedToken("https://idp.test", ALICE, Set.of("oa-api"),
                Set.of("oa.audit.read", "oa.audit.verify"), "tenant-a", clock.instant(),
                clock.instant().plus(Duration.ofMinutes(5)), "jti-1", List.of());
        resource = resource(token, pepOk());
    }

    private Pep pepOk() {
        return new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "ok", new PolicyRef("test.permit", "1"),
                clock.instant()), (AuditEvent event) -> duringAudit.run());
    }

    private AuditResource resource(VerifiedToken token, Pep pep) {
        return resource(token, Instance(true, pep));
    }

    @SuppressWarnings("unchecked")
    private static Instance<Pep> Instance(boolean resolvable, Pep pep) {
        return (Instance<Pep>) Proxy.newProxyInstance(Instance.class.getClassLoader(), new Class<?>[] {Instance.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isResolvable" -> resolvable;
                    case "get" -> {
                        if (pep == null) {
                            throw new IllegalStateException("policy engine could not be built");
                        }
                        yield pep;
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private AuditResource resource(VerifiedToken token, Instance<Pep> peps) {
        Instance<io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint> none =
                (Instance<io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint>)
                        Proxy.newProxyInstance(Instance.class.getClassLoader(), new Class<?>[] {Instance.class},
                                (p, m, a) -> m.getName().equals("isResolvable") ? false : null);
        javax.sql.DataSource data = (javax.sql.DataSource) Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
                (p, m, a) -> {
                    if (m.getName().equals("getConnection")) {
                        connections.incrementAndGet();
                        throw new SQLException("no database in this test");
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        AuditResource r = new AuditResource();
        r.access = new AuditAccess(none, peps, false);
        r.callers = new CallerContextResolver((bearer, audience) -> token, "oa-api", false, Set.of("test"),
                Set.of("test"), clock);
        r.query = new AuditQuery(data);
        r.verifier = new ChainVerifier(data);
        return r;
    }

    private static HttpHeaders bearer() {
        return (HttpHeaders) Proxy.newProxyInstance(HttpHeaders.class.getClassLoader(),
                new Class<?>[] {HttpHeaders.class}, (p, m, a) -> {
                    if (m.getName().equals("getRequestHeader")) {
                        return List.of("Bearer a.b.c");
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    private int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (WebApplicationException e) {
            return e.getResponse().getStatus();
        } catch (RuntimeException other) {
            return 0; // a database failure after the freshness check: I/O was attempted
        }
    }

    private void crossExpiryDuringAudit() {
        duringAudit = () -> clock.advance(Duration.ofMinutes(6));
    }

    @Test
    void eventsPastSignedExpiryAfterTheBlockedAuditIsRefusedWithoutAnyRead() {
        crossExpiryDuringAudit();
        assertEquals(401, status(() -> resource.events(bearer(), null, null, null, null, null, null, null, null)));
        assertEquals(0, connections.get());
    }

    @Test
    void oneEventPastSignedExpiryAfterTheBlockedAuditIsRefusedWithoutAnyRead() {
        crossExpiryDuringAudit();
        assertEquals(401, status(() -> resource.event(bearer(), "01ARZ3NDEKTSV4RRFFQ69G5FAV")));
        assertEquals(0, connections.get());
    }

    @Test
    void verifyPastSignedExpiryAfterTheBlockedAuditIsRefusedWithoutAnyVerification() {
        crossExpiryDuringAudit();
        assertEquals(401, status(() -> resource.verify(bearer(), null)));
        assertEquals(0, connections.get());
    }

    @Test
    void aStillValidCallerReachesTheStoreOnce() {
        status(() -> resource.events(bearer(), null, null, null, null, null, null, null, null));
        assertEquals(1, connections.get()); // the positive path proves the doubles would have counted a read
    }

    @Test
    void anEngineThatCannotBeBuiltIsA503NotA500AndReadsNothing() {
        VerifiedToken token = new VerifiedToken("https://idp.test", ALICE, Set.of("oa-api"), Set.of("oa.audit.read"),
                "tenant-a", clock.instant(), clock.instant().plus(Duration.ofMinutes(5)), "jti-2", List.of());
        AuditResource broken = resource(token, Instance(true, null));
        WebApplicationException refused = assertThrows(WebApplicationException.class,
                () -> broken.events(bearer(), null, null, null, null, null, null, null, null));
        assertEquals(503, refused.getResponse().getStatus());
        assertEquals(0, connections.get());
    }
}
