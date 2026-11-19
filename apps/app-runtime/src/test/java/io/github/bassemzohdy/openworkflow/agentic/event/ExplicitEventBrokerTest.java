package io.github.bassemzohdy.openworkflow.agentic.event;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.events.InMemoryEvents;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** An explicitly selected deployment transport must not be replaced or paired with a second local publisher. */
@QuarkusTest
@TestProfile(ExplicitEventBrokerTest.Profile.class)
class ExplicitEventBrokerTest {
    public static final class Profile implements QuarkusTestProfile {
        @Override public Set<Class<?>> getEnabledAlternatives() { return Set.of(ConfiguredBroker.class); }
    }

    @Alternative
    @Singleton
    public static class ConfiguredBroker extends InMemoryEvents { }

    @Inject WorkflowApplication application;

    @Test
    void explicitlyConfiguredTransportRemainsTheApplicationConsumerAndOnlyPublisher() {
        var configured = assertInstanceOf(ConfiguredBroker.class, application.eventConsumer());
        assertEquals(1, application.eventPublishers().size());
        assertSame(configured, application.eventPublishers().iterator().next());
    }
}
