package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrantStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrants;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.IdentityAudit;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.JwtTokenVerifier;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.SecretProvider;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.SecretUnavailableException;
import io.github.bassemzohdy.openworkflow.agentic.testkit.time.ControllableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Shared set-up of the identity tests in this module: the database, a fake issuer, the test authorization server, a
 * verifier for the issuer's tokens, real {@link DelegationGrants}. Everything is in-process; nothing here is a runtime
 * dependency. The database is the one the other tests use (DB_URL).
 */
final class IdentityFixture implements AutoCloseable {

    static final String URL = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic");
    static final String USER = System.getenv().getOrDefault("DB_USER", "postgres");
    static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "postgres");
    static final String CLIENT_ID = "oa-agent-runtime";
    static final String CLIENT_SECRET_NAME = "exchange-client-secret";
    static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    static final Subject PLANNER = new Subject(SubjectKind.AGENT, "planner");
    static final Subject WORKER = new Subject(SubjectKind.AGENT, "worker");

    private static DataSource sharedDataSource;

    final ControllableClock clock = new ControllableClock(Instant.parse("2026-10-19T10:00:00Z"));
    final String tenant = "t-" + UUID.randomUUID();
    final FakeIdentityProvider idp;
    final DelegationAuthorizationServer as;
    final JwtTokenVerifier verifier;
    final RecordingSink sink = new RecordingSink();
    final DelegationGrants grants;
    final FakeSecrets secrets = new FakeSecrets();
    final String clientSecret = "s3cret-" + UUID.randomUUID();

    IdentityFixture(String... registeredAudiences) throws Exception {
        idp = new FakeIdentityProvider(clock).start();
        as = new DelegationAuthorizationServer(idp, 0, CLIENT_ID, clientSecret, Set.of(registeredAudiences));
        verifier = JwtTokenVerifier.forJwks(idp.issuer(), idp.jwksUri(), clock, Duration.ofSeconds(30), "tenant",
                "sub_kind", Duration.ofSeconds(2));
        grants = new DelegationGrants(new DelegationGrantStore(dataSource()), audit(), clock, UlidGenerator.shared(),
                Duration.ofDays(30));
        secrets.put(CLIENT_SECRET_NAME, clientSecret);
    }

    IdentityAudit audit() {
        return new IdentityAudit(sink, clock, UlidGenerator.shared());
    }

    static synchronized DataSource dataSource() {
        if (sharedDataSource == null) {
            Flyway.configure().dataSource(URL, USER, PASSWORD).locations("classpath:db/migration").load().migrate();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(URL);
            ds.setUser(USER);
            ds.setPassword(PASSWORD);
            sharedDataSource = ds;
        }
        return sharedDataSource;
    }

    @Override
    public void close() {
        as.close();
        idp.close();
    }

    /** Audit events in memory, with a switch to make the trail fail. */
    static final class RecordingSink implements AuditSink {

        final List<AuditEvent> events = new ArrayList<>();
        volatile boolean failing;

        @Override
        public synchronized void record(AuditEvent event) {
            if (failing) {
                throw new IllegalStateException("audit trail down");
            }
            events.add(event);
        }

        synchronized List<AuditEvent> byAction(String action) {
            return events.stream().filter(e -> e.action().equals(action)).toList();
        }

        synchronized String everything() {
            return events.toString();
        }
    }

    /** Secrets in memory; an absent name is unavailable. */
    static final class FakeSecrets implements SecretProvider {

        private final Map<String, String> values = new ConcurrentHashMap<>();

        void put(String name, String value) {
            values.put(name, value);
        }

        void remove(String name) {
            values.remove(name);
        }

        @Override
        public Credential current(String name) {
            String value = values.get(name);
            if (value == null) {
                throw new SecretUnavailableException("secret '" + name + "' is not available");
            }
            return Credential.of(value);
        }
    }
}
