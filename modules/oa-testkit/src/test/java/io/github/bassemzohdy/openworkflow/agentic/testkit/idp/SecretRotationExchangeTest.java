package io.github.bassemzohdy.openworkflow.agentic.testkit.idp;

import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.ALICE;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_ID;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.CLIENT_SECRET_NAME;
import static io.github.bassemzohdy.openworkflow.agentic.testkit.idp.IdentityFixture.PLANNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Credential;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.DelegationGrant;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.ExchangeRequest;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.FileSecretProvider;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.Rfc8693TokenExchanger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The exchanger with the real {@link FileSecretProvider}: a rotated client secret is picked up without a restart, and
 * when the secret cannot be read, dependent work stops (no request, no stale value used for ever).
 */
class SecretRotationExchangeTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration STALE = Duration.ofMinutes(2);

    @TempDir
    Path secretsDir;

    private IdentityFixture fx;
    private Rfc8693TokenExchanger exchanger;
    private ExchangeRequest request;

    @BeforeEach
    void start() throws Exception {
        fx = new IdentityFixture("tool-cases");
        Files.writeString(secretsDir.resolve(CLIENT_SECRET_NAME), fx.clientSecret + "\n");
        FileSecretProvider provider = new FileSecretProvider(secretsDir, fx.clock, TTL, STALE, fx.audit());
        exchanger = new Rfc8693TokenExchanger(fx.as.tokenEndpoint(), CLIENT_ID, CLIENT_SECRET_NAME, provider, fx.grants, fx.bindings,
                fx.verifier, fx.audit(), fx.clock, Duration.ofSeconds(2));
        DelegationGrant grant = fx.grants.grant(fx.tenant, ALICE, ALICE, PLANNER, Set.of("cases:read"),
                fx.clock.instant().plus(Duration.ofHours(1)), null);
        request = new ExchangeRequest(
                Credential.of(fx.as.userToken("alice", fx.tenant, "oa-api", Set.of("cases:read"), Duration.ofHours(1))),
                Credential.of(fx.as.workloadToken("planner", fx.tenant, Duration.ofHours(1))), "tool-cases",
                Set.of("cases:read"), grant);
    }

    @AfterEach
    void stop() {
        fx.close();
    }

    @Test
    void aRotatedClientSecretFileIsUsedByTheNextExchangeWithoutARestart() throws Exception {
        assertNotNull(exchanger.exchange(request));

        fx.as.rotateClientSecret("rotated-secret");
        Files.writeString(secretsDir.resolve(CLIENT_SECRET_NAME), "rotated-secret\n");
        Files.setLastModifiedTime(secretsDir.resolve(CLIENT_SECRET_NAME),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        assertNotNull(exchanger.exchange(request));
    }

    @Test
    void whenTheSecretFileDisappearsExchangesStopAtOnceAndNoRequestIsSent() throws Exception {
        assertNotNull(exchanger.exchange(request));
        int before = fx.as.requestCount();

        Files.delete(secretsDir.resolve(CLIENT_SECRET_NAME));

        ExchangeException e = assertThrows(ExchangeException.class, () -> exchanger.exchange(request));
        assertEquals(ExchangeException.Code.UNAVAILABLE, e.code());
        assertEquals(before, fx.as.requestCount(), "nothing reaches the server without a secret");
    }

    @Test
    void whenTheSecretCannotBeReadTheCachedOneIsUsedOnlyForTheBoundedGraceAndThenWorkStops() throws Exception {
        assertNotNull(exchanger.exchange(request));
        Path file = secretsDir.resolve(CLIENT_SECRET_NAME);
        Files.delete(file);
        Files.createDirectory(file);                 // unreadable as a secret, but not "missing"

        assertNotNull(exchanger.exchange(request), "a transient fault does not stop work immediately");

        fx.clock.advance(TTL.plus(STALE).plusSeconds(1));
        // fresh subject and actor tokens: only the secret is at fault
        request = new ExchangeRequest(
                Credential.of(fx.as.userToken("alice", fx.tenant, "oa-api", Set.of("cases:read"), Duration.ofHours(1))),
                Credential.of(fx.as.workloadToken("planner", fx.tenant, Duration.ofHours(1))), "tool-cases",
                Set.of("cases:read"), request.grant());
        int before = fx.as.requestCount();
        ExchangeException e = assertThrows(ExchangeException.class, () -> exchanger.exchange(request));
        assertEquals(ExchangeException.Code.UNAVAILABLE, e.code());
        assertEquals(before, fx.as.requestCount());

        Files.delete(file);
        Files.writeString(file, fx.clientSecret);
        assertNotNull(exchanger.exchange(request), "work resumes when the secret is readable again");
    }
}
