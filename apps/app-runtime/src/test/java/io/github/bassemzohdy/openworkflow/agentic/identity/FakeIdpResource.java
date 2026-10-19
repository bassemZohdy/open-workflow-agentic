package io.github.bassemzohdy.openworkflow.agentic.identity;

import io.github.bassemzohdy.openworkflow.agentic.testkit.idp.FakeIdentityProvider;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.time.Clock;
import java.util.Map;

/**
 * Starts the testkit's fake issuer on a fixed loopback port (18492, reserved for this work package) and points the
 * application at it. The application then verifies real RS256 signatures against the fake's JWKS over HTTP. This is the
 * "verified against the fake" boundary of docs/identity.md: the application code under test is real, the issuer is not.
 */
public class FakeIdpResource implements QuarkusTestResourceLifecycleManager {

    public static final int PORT = 18492;
    public static final String AUDIENCE = "oa-api";

    private static FakeIdentityProvider idp;

    /** The running fake. Valid while the test application runs. */
    public static FakeIdentityProvider idp() {
        return idp;
    }

    @Override
    public Map<String, String> start() {
        try {
            idp = new FakeIdentityProvider(Clock.systemUTC()).start(PORT);
        } catch (Exception e) {
            throw new IllegalStateException("cannot start the fake identity provider on port " + PORT, e);
        }
        return Map.of(
                "agentic.identity.issuer", idp.issuer(),
                "agentic.identity.jwks-uri", idp.jwksUri(),
                "agentic.identity.audience", AUDIENCE);
    }

    @Override
    public void stop() {
        if (idp != null) {
            idp.close();
            idp = null;
        }
    }
}
