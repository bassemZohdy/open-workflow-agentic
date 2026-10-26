package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * WP-103: private audit output is withheld when the signed identity of the caller expires WHILE the blocking query or
 * verification runs. The resolver, access, PEP, resource, {@link AuditQuery}, {@link ChainVerifier} and {@link AuditTrail}
 * are the real classes on a real PostgreSQL in a private, throwaway, migrated database. The only double is a data-source
 * wrapper that moves the test clock past the token expiry when a connection is closed, i.e. after the read has finished
 * and before the resource returns. It proves signed expiry (401); it does not prove a fresh policy decision, tenant
 * filtering, emergency state or actual-root/async authority.
 */
class AuditResourceOutputFreshnessTest {

    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final String DATABASE = "oa_audit_fresh_" + UUID.randomUUID().toString().replace("-", "");
    private static PGSimpleDataSource real;

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
    private AtomicInteger opened;
    private AtomicBoolean expireAfterRead;
    private AuditResource resource;
    private String chain;
    private AuditEvent stored;

    @BeforeAll
    static void createPrivateDatabase() throws Exception {
        try (Connection c = source("postgres").getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + DATABASE);
        }
        real = source(DATABASE);
        Flyway.configure().dataSource(real).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void dropPrivateDatabase() throws Exception {
        try (Connection c = source("postgres").getConnection(); Statement s = c.createStatement()) {
            s.execute("drop database " + DATABASE + " with (force)");
        }
    }

    private static PGSimpleDataSource source(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(AuditTestDatabase.URL);
        ds.setUser(AuditTestDatabase.USER);
        ds.setPassword(AuditTestDatabase.PASSWORD);
        ds.setDatabaseName(database);
        return ds;
    }

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        opened = new AtomicInteger();
        expireAfterRead = new AtomicBoolean();
        chain = "t" + AuditTestDatabase.newId().value();
        stored = new AuditTrail(real).append(AuditDraft.of(AuditTestDatabase.newId(),
                Instant.parse("2026-10-18T10:00:00Z"), AuditTestDatabase.actor(), "tool.call.allowed", "run", "r-1",
                AuditTestDatabase.attributes(chain, Map.of())));
        VerifiedToken token = new VerifiedToken("https://idp.test", ALICE, Set.of("oa-api"),
                Set.of("oa.audit.read", "oa.audit.verify"), "tenant-a", clock.instant(),
                clock.instant().plus(Duration.ofMinutes(5)), "jti-1", List.of());
        resource = resource(token);
    }

    /** Delegates to the real database; closing a connection (the read is done) may carry the clock past expiry. */
    private DataSource clockMovingSource() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (p, m, a) -> {
                    Object result = invoke(m, real, a);
                    if (!m.getName().equals("getConnection")) {
                        return result;
                    }
                    opened.incrementAndGet();
                    Connection connection = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("close") && expireAfterRead.get()) {
                                    clock.advance(Duration.ofMinutes(6));
                                }
                                return invoke(cm, connection, ca);
                            });
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private AuditResource resource(VerifiedToken token) {
        Pep pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "ok",
                new PolicyRef("test.permit", "1"), clock.instant()), (AuditEvent event) -> { });
        Instance<Pep> peps = (Instance<Pep>) Proxy.newProxyInstance(Instance.class.getClassLoader(),
                new Class<?>[] {Instance.class}, (p, m, a) -> switch (m.getName()) {
                    case "isResolvable" -> true;
                    case "get" -> pep;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Instance<io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint> none =
                (Instance<io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint>)
                        Proxy.newProxyInstance(Instance.class.getClassLoader(), new Class<?>[] {Instance.class},
                                (p, m, a) -> m.getName().equals("isResolvable") ? false : null);
        DataSource data = clockMovingSource();
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

    private static int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (WebApplicationException e) {
            return e.getResponse().getStatus();
        }
    }

    private AuditResource.EventPage page() {
        return resource.events(bearer(), null, null, chain, null, null, null, null, null);
    }

    // The read finishes after the token has expired: nothing leaves.

    @Test
    void anEventsPageReadAcrossTheExpiryIsWithheld() {
        expireAfterRead.set(true);
        assertEquals(401, status(this::page));
        assertTrue(opened.get() > 0, "the query really ran before the output was withheld");
    }

    @Test
    void oneEventReadAcrossTheExpiryIsWithheld() {
        expireAfterRead.set(true);
        assertEquals(401, status(() -> resource.event(bearer(), stored.id().value())));
        assertTrue(opened.get() > 0);
    }

    @Test
    void anUnknownEventIsNotAnExistenceSignalAfterTheExpiry() {
        expireAfterRead.set(true);
        assertEquals(401, status(() -> resource.event(bearer(), AuditTestDatabase.newId().value())));
    }

    @Test
    void oneChainVerificationAcrossTheExpiryIsWithheld() {
        expireAfterRead.set(true);
        assertEquals(401, status(() -> resource.verify(bearer(), chain)));
        assertTrue(opened.get() > 0);
    }

    @Test
    void allChainsVerificationAcrossTheExpiryIsWithheld() {
        expireAfterRead.set(true);
        assertEquals(401, status(() -> resource.verify(bearer(), null)));
        assertTrue(opened.get() > 0);
    }

    // Positive output and the unchanged before-I/O refusals.

    @Test
    void aStillValidCallerGetsTheRealPageEventAndVerification() {
        AuditResource.EventPage page = page();
        assertEquals(1, page.items().size());
        assertEquals(stored.id().value(), page.items().get(0).eventId());
        assertEquals(stored.id().value(), resource.event(bearer(), stored.id().value()).eventId());
        AuditResource.VerifyResult one = resource.verify(bearer(), chain);
        assertTrue(one.intact());
        assertEquals(1, one.chains().size());
        assertTrue(resource.verify(bearer(), null).chains().stream().anyMatch(c -> c.chainId().equals(chain)));
    }

    @Test
    void aMissingEventIsStillA404ForAValidCaller() {
        assertEquals(404, status(() -> resource.event(bearer(), AuditTestDatabase.newId().value())));
    }

    @Test
    void anAlreadyExpiredTokenStillReadsNothing() {
        clock.advance(Duration.ofMinutes(6));
        assertEquals(401, status(this::page));
        assertEquals(401, status(() -> resource.event(bearer(), stored.id().value())));
        assertEquals(401, status(() -> resource.verify(bearer(), null)));
        assertEquals(0, opened.get());
    }

    @Test
    void aBadArgumentStillReadsNothing() {
        assertEquals(400, status(() -> resource.verify(bearer(), "not a chain id")));
        assertEquals(400, status(() -> resource.event(bearer(), "not-a-ulid")));
        assertFalse(opened.get() > 0, "an argument error is refused before any store access");
    }
}
