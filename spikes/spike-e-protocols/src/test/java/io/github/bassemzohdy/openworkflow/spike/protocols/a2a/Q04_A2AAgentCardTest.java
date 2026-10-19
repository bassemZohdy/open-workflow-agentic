package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WP-007 question 4 (part): Agent Card model and serving. The model test passes; the served-card
 * tests are disabled because the A2A reference server does not come up in a Quarkus test app
 * (see docs/spikes/WP-007-mcp-a2a-protocols.md, Q04/Q05).
 */
@QuarkusTest
class Q04_A2AAgentCardTest {

    @TestHTTPResource("/")
    URL base;

    @Test
    void Q04_agentCardModelCarriesProtocolVersionAndInterface() {
        AgentCard card = new CaseAgentCard().agentCard();
        assertEquals("case-agent", card.name());
        assertEquals(AgentInterface.CURRENT_PROTOCOL_VERSION, "1.0");
        assertEquals("1.0", card.supportedInterfaces().get(0).protocolVersion());
        assertEquals("JSONRPC", card.supportedInterfaces().get(0).protocolBinding());
        assertTrue(card.capabilities().streaming(), "streaming capability advertised");
    }

    @Test
    @Disabled("Blocked (issue #25): Observed 404 on /.well-known/agent-card.json. A2AServerRoutes is a @Singleton "
            + "with an @Observes Router method, but JSONRPCHandler and VertxSecurityHelper have no bean-defining "
            + "annotation and were not produced. CaseA2AWiring produces them but was not run: Quarkus augmentation "
            + "stalls in ArC AutoAddScope bean discovery under -Pa2a on this host. See WP-007, In-process wiring.")
    void Q04_agentCardServedAtWellKnownPathWithProtocolVersion() throws Exception {
        // Intended check: GET <base>/.well-known/agent-card.json returns 200 with supportedInterfaces[0].protocolVersion "1.0".
        // Not run: see the @Disabled reason.
        assertEquals("1.0", AgentInterface.CURRENT_PROTOCOL_VERSION);
    }
}
