package io.github.bassemzohdy.openworkflow.agentic.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedTokenBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IssuedTokenBindings.Binding;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JdbcIssuedTokenBindings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The grant binding against real PostgreSQL (WP-102 H4): the schema is the shared migration, the class under test is the
 * one the application injects (with the JTA transaction manager).
 */
@QuarkusTest
class JdbcIssuedTokenBindingsTest {

    private static final String ISSUER = "http://issuer.test/realms/it";

    @Inject IssuedTokenBindings bindings;
    @Inject DelegationGrants grants;
    @Inject DataSource dataSource;
    @Inject UserTransaction transaction;

    private DelegationGrant grant(String tenant) {
        Subject user = new Subject(SubjectKind.USER, "alice-" + UUID.randomUUID());
        return grants.grant(tenant, user, user, new Subject(SubjectKind.AGENT, "planner"), Set.of("cases:read"),
                Instant.now().plus(Duration.ofHours(1)), null);
    }

    private static Instant exp() {
        return Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String jti() {
        return "jti-" + UUID.randomUUID();
    }

    @Test
    void aBindingIsStoredWithGrantTenantAndSignedExpiryAndAnIdenticalRetryIsAccepted() {
        DelegationGrant grant = grant("tenant-b1");
        String jti = jti();
        Binding binding = new Binding(grant.id(), grant.tenant(), exp());

        bindings.bind(ISSUER, jti, binding);
        bindings.bind(ISSUER, jti, binding);

        assertEquals(binding, bindings.find(ISSUER, jti).orElseThrow());
        assertTrue(bindings.find(ISSUER, jti + "-other").isEmpty());
        assertTrue(bindings.find(ISSUER + "x", jti).isEmpty(), "the issuer is part of the key");
    }

    @Test
    void aDifferentGrantTenantOrExpiryForTheSameIssuerAndJtiIsRefusedAndNothingChanges() {
        DelegationGrant grant = grant("tenant-b2");
        DelegationGrant other = grant("tenant-b2");
        String jti = jti();
        Binding binding = new Binding(grant.id(), grant.tenant(), exp());
        bindings.bind(ISSUER, jti, binding);

        for (Binding changed : List.of(
                new Binding(grant.id(), grant.tenant(), binding.expiresAt().plusSeconds(1)),
                new Binding(other.id(), other.tenant(), binding.expiresAt()))) {
            assertThrows(IllegalStateException.class, () -> bindings.bind(ISSUER, jti, changed));
        }
        // a tenant other than the grant's is refused by the schema itself (foreign key on grant and tenant)
        assertThrows(IllegalStateException.class,
                () -> bindings.bind(ISSUER, jti(), new Binding(grant.id(), "tenant-other", exp())));
        assertEquals(binding, bindings.find(ISSUER, jti).orElseThrow());
    }

    @Test
    void manyConcurrentBindsOfOneTokenToDifferentGrantsLetExactlyOneWin() throws Exception {
        String jti = jti();
        int n = 8;
        List<DelegationGrant> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            candidates.add(grant("tenant-b3"));
        }
        Instant exp = exp();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (DelegationGrant g : candidates) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        bindings.bind(ISSUER, jti, new Binding(g.id(), g.tenant(), exp));
                        return true;
                    } catch (IllegalStateException conflict) {
                        return false;
                    }
                }));
            }
            go.countDown();
            long winners = 0;
            for (Future<Boolean> r : results) {
                winners += r.get() ? 1 : 0;
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
        Binding stored = bindings.find(ISSUER, jti).orElseThrow();
        assertTrue(candidates.stream().anyMatch(g -> g.id().equals(stored.grantId())));
    }

    @Test
    void aCallerThatRollsBackCannotEraseABindingThatWasAlreadyWritten() throws Exception {
        DelegationGrant grant = grant("tenant-b4");
        String jti = jti();
        Binding binding = new Binding(grant.id(), grant.tenant(), exp());

        transaction.begin();
        try {
            bindings.bind(ISSUER, jti, binding);
        } finally {
            transaction.rollback();
        }

        // read through a separate, plain connection: the binding is durable although the caller rolled back
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(
                "select count(*) from identity.issued_token_binding where issuer = ? and token_id = ?")) {
            ps.setString(1, ISSUER);
            ps.setString(2, jti);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
        assertEquals(binding, bindings.find(ISSUER, jti).orElseThrow());
    }

    @Test
    void aWriteDoesNotCommitTheCallersUnrelatedWork() throws Exception {
        DelegationGrant grant = grant("tenant-b5");
        String marker = "caller-" + UUID.randomUUID();
        transaction.begin();
        try {
            try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(
                    "insert into identity.token_use (token_key, uses, expires_at) values (?, 1, now() + interval '1 hour')")) {
                ps.setString(1, marker);
                ps.executeUpdate();
            }
            bindings.bind(ISSUER, jti(), new Binding(grant.id(), grant.tenant(), exp()));
        } finally {
            transaction.rollback();
        }
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement(
                "select count(*) from identity.token_use where token_key = ?")) {
            ps.setString(1, marker);
            try (var rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "the caller's own write was rolled back, not committed by the binding");
            }
        }
    }

    @Test
    void anUnreachableDatabaseIsAFailureForBothReadAndWrite() {
        DataSource dead = new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                throw new SQLException("down");
            }
            @Override public Connection getConnection(String u, String p) throws SQLException {
                throw new SQLException("down");
            }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
        JdbcIssuedTokenBindings broken = new JdbcIssuedTokenBindings(dead);
        DelegationGrant grant = grant("tenant-b6");

        assertThrows(IllegalStateException.class,
                () -> broken.bind(ISSUER, jti(), new Binding(grant.id(), grant.tenant(), exp())));
        assertThrows(IllegalStateException.class, () -> broken.find(ISSUER, jti()));
    }
}
