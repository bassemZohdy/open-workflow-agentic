package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** SQL-level shared schema guarantees; does not claim product exchanger/guard or real-provider acceptance. */
@QuarkusTest
class IssuedTokenBindingMigrationTest {
    @Inject DataSource dataSource;

    private String grant(String tenant) throws SQLException {
        String id = UlidGenerator.shared().next().value();
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                insert into identity.delegation_grant
                (id,tenant,subject_kind,subject_id,agent_kind,agent_id,scopes,depth,chain,status,
                 created_at,created_by_kind,created_by_id,expires_at)
                values (?,?,'USER','binding-user','AGENT','binding-agent',array['cases:read'],0,
                        array['binding-agent'],'ACTIVE',clock_timestamp(),'USER','binding-user',
                        clock_timestamp()+interval '1 hour')
                """)) {
            s.setString(1, id); s.setString(2, tenant); s.executeUpdate();
        }
        return id;
    }

    private void bind(String issuer, String token, String grant, String tenant, Instant expires) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("""
                insert into identity.issued_token_binding (issuer,token_id,grant_id,tenant,expires_at) values (?,?,?,?,?)
                """)) {
            s.setString(1, issuer); s.setString(2, token); s.setString(3, grant); s.setString(4, tenant);
            s.setTimestamp(5, Timestamp.from(expires)); s.executeUpdate();
        }
    }

    private int change(String sql, String issuer) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement(sql)) {
            s.setString(1, issuer); return s.executeUpdate();
        }
    }

    private String boundGrant(String issuer, String token) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement(
                "select grant_id from identity.issued_token_binding where issuer=? and token_id=?")) {
            s.setString(1, issuer); s.setString(2, token);
            try (var r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    @Test
    void issuerScopesTheTokenIdAndAnExistingBindingCannotChangeGrant() throws Exception {
        String tenant = "binding-" + UUID.randomUUID(), issuer = "urn:test:" + UUID.randomUUID();
        String first = grant(tenant), equivalent = grant(tenant);
        bind(issuer, "same-jti", first, tenant, Instant.now().plusSeconds(300));
        bind(issuer + ":other", "same-jti", equivalent, tenant, Instant.now().plusSeconds(300));
        assertEquals("23505", assertThrows(SQLException.class,
                () -> bind(issuer, "same-jti", equivalent, tenant, Instant.now().plusSeconds(300))).getSQLState());
        assertEquals(first, boundGrant(issuer, "same-jti"));
        assertEquals(equivalent, boundGrant(issuer + ":other", "same-jti"));
    }

    @Test
    void grantTenantForeignKeyRefusesMismatchedOrAbsentAuthority() throws Exception {
        String tenant = "binding-" + UUID.randomUUID(), issuer = "urn:test:" + UUID.randomUUID();
        String id = grant(tenant);
        assertEquals("23503", assertThrows(SQLException.class,
                () -> bind(issuer, "wrong-tenant", id, tenant + "-other", Instant.now().plusSeconds(300))).getSQLState());
        assertEquals("23503", assertThrows(SQLException.class,
                () -> bind(issuer, "absent", UlidGenerator.shared().next().value(), tenant,
                        Instant.now().plusSeconds(300))).getSQLState());
        assertNull(boundGrant(issuer, "wrong-tenant")); assertNull(boundGrant(issuer, "absent"));
    }

    @Test
    void updateEarlyDeleteAndTruncateCannotRemoveOrRewriteLiveProof() throws Exception {
        String tenant = "binding-" + UUID.randomUUID(), issuer = "urn:test:" + UUID.randomUUID();
        String id = grant(tenant);
        bind(issuer, "live", id, tenant, Instant.now().plusSeconds(300));
        assertEquals("42501", assertThrows(SQLException.class, () -> change(
                "update identity.issued_token_binding set expires_at=expires_at where issuer=?", issuer)).getSQLState());
        assertEquals("42501", assertThrows(SQLException.class, () -> change(
                "delete from identity.issued_token_binding where issuer=?", issuer)).getSQLState());
        // Always roll back this assertion, even if a regression permits TRUNCATE; never remove other fixtures.
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class,
                        // V73 retains issuing rows through this child FK. Include the child so this still exercises
                        // the immutable-table trigger, rather than PostgreSQL's earlier FK TRUNCATE refusal.
                        () -> s.executeUpdate("truncate identity.issued_token_binding, task.resource_call_binding")).getSQLState());
            } finally { c.rollback(); }
        }
        assertEquals(id, boundGrant(issuer, "live"));
    }

    @Test
    void purgeRetainsRecentlyExpiredProofButCanRemoveOlderExpiredRows() throws Exception {
        String tenant = "binding-" + UUID.randomUUID(), issuer = "urn:test:" + UUID.randomUUID();
        String id = grant(tenant);
        bind(issuer, "recent", id, tenant, Instant.now().minusSeconds(10));
        bind(issuer + ":old", "expired", id, tenant, Instant.now().minusSeconds(180));
        assertEquals("42501", assertThrows(SQLException.class, () -> change(
                "delete from identity.issued_token_binding where issuer=?", issuer)).getSQLState());
        assertEquals(1, change("delete from identity.issued_token_binding where issuer=?", issuer + ":old"));
        assertEquals(id, boundGrant(issuer, "recent"));
        assertNull(boundGrant(issuer + ":old", "expired"));
    }

    @Test
    void boundedNonblankKeysFitThePostgresIndexEvenWithFourByteCharacters() throws Exception {
        String tenant = "binding-" + UUID.randomUUID(), id = grant(tenant);
        String issuer = "urn:" + UUID.randomUUID() + ":" + "\uD834\uDD1E".repeat(250);
        bind(issuer, "\uD83D\uDE80".repeat(300), id, tenant, Instant.now().plusSeconds(300));
        for (String invalid : new String[]{"", "   ", "\t\r\n", "x".repeat(301)}) {
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> bind(invalid, UUID.randomUUID().toString(), id, tenant,
                            Instant.now().plusSeconds(300))).getSQLState());
            assertEquals("23514", assertThrows(SQLException.class,
                    () -> bind(issuer, invalid, id, tenant, Instant.now().plusSeconds(300))).getSQLState());
        }
    }
}
