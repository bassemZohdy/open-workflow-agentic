package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityTestSupport.MovableClock;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * {@link DelegationGrants#check} judges expiry after its blocking lineage reads, over real rows in a private database.
 * It proves the expiry fence only; status and revocation are still read grant by grant.
 */
class DelegationGrantExpiryAfterIoTest {

    private static String dbName;
    private static String adminUrl;
    private static DataSource data;

    private final Subject alice = IdentityTestSupport.user("alice");
    private final Subject planner = IdentityTestSupport.agent("planner");
    private final Subject worker = IdentityTestSupport.agent("worker");
    private MovableClock clock;
    private DelegationGrants grants;
    private String tenant;
    private Consumer<Ulid> afterRead;

    @BeforeAll
    static void privateDatabase() throws Exception {
        String url = IdentityTestSupport.URL;
        String base = url.substring(0, url.lastIndexOf('/') + 1);
        adminUrl = base + "postgres";
        dbName = "oa_wp102_expiry_" + UUID.randomUUID().toString().replace("-", "");
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
        afterRead = id -> { };
        clock = new MovableClock(Instant.parse("2026-10-20T10:00:00Z"));
        grants = new DelegationGrants(new DelegationGrantStore(data) {
            @Override
            public Optional<DelegationGrant> find(Ulid id) {
                Optional<DelegationGrant> found = super.find(id); // a real row, read before the delay
                afterRead.accept(id);
                return found;
            }
        }, IdentityTestSupport.audit(new IdentityTestSupport.RecordingSink(), clock), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        tenant = IdentityTestSupport.newTenant();
    }

    private DelegationGrant root(Duration lifetime) {
        return grants.grant(tenant, alice, alice, planner, Set.of("cases:read"), clock.instant().plus(lifetime), null);
    }

    private DelegationGrant child(DelegationGrant parent, Duration lifetime) {
        return grants.delegate(parent.id(), planner, worker, Set.of("cases:read"), clock.instant().plus(lifetime),
                null);
    }

    private void delayAfterReadOf(Ulid id, Duration by) {
        boolean[] fired = {false};
        afterRead = read -> {
            if (read.equals(id) && !fired[0]) {
                fired[0] = true;
                clock.advance(by);
            }
        };
    }

    @Test
    void aGrantThatExpiresDuringItsOwnReadIsExpired() {
        DelegationGrant grant = root(Duration.ofMinutes(2));
        delayAfterReadOf(grant.id(), Duration.ofMinutes(2)); // exactly at expiry
        GrantCheck.Inactive inactive = assertInstanceOf(GrantCheck.Inactive.class, grants.check(grant.id()));
        assertEquals(GrantCheck.Reason.EXPIRED, inactive.reason());
    }

    @Test
    void aChildThatExpiresWhileTheAncestorIsReadIsExpired() {
        DelegationGrant parent = root(Duration.ofHours(1));
        DelegationGrant child = child(parent, Duration.ofMinutes(2));
        delayAfterReadOf(parent.id(), Duration.ofMinutes(2));
        GrantCheck.Inactive inactive = assertInstanceOf(GrantCheck.Inactive.class, grants.check(child.id()));
        assertEquals(GrantCheck.Reason.EXPIRED, inactive.reason());
    }

    @Test
    void anActiveChainOneMicrosecondBeforeExpiryAfterTheReadsIsActive() {
        DelegationGrant parent = root(Duration.ofHours(1));
        DelegationGrant child = child(parent, Duration.ofMinutes(2));
        delayAfterReadOf(parent.id(), Duration.ofMinutes(2).minusNanos(1_000));
        GrantCheck check = grants.check(child.id());
        assertTrue(check.active());
        assertEquals(child, ((GrantCheck.Active) check).grant());
    }

    @Test
    void typedOutcomesForUnknownAndExpiredAtStartAreUnchanged() {
        GrantCheck unknown = grants.check(UlidGenerator.shared().next());
        assertEquals(GrantCheck.Reason.UNKNOWN, ((GrantCheck.Inactive) unknown).reason());
        DelegationGrant parent = root(Duration.ofMinutes(2));
        DelegationGrant child = child(parent, Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(1));
        assertEquals(GrantCheck.Reason.EXPIRED, ((GrantCheck.Inactive) grants.check(child.id())).reason());
        assertTrue(grants.check(parent.id()).active());
    }
}
