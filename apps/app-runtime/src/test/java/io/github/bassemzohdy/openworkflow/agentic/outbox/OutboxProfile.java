package io.github.bassemzohdy.openworkflow.agentic.outbox;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * This process does not run the outbox relay. The tests drive their own relays with {@code drainOnce}, so the state of
 * the outbox at each assertion is known and does not depend on a background thread.
 */
public class OutboxProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("agentic.outbox.relay.enabled", "false");
    }
}
