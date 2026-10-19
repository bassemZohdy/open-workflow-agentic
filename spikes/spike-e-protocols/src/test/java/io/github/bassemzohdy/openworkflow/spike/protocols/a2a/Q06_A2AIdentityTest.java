package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WP-007 question 6: how a delegated identity travels in A2A calls. The client sends a per-call
 * Authorization header through ClientCallContext; the server maps it to a User via a CallContextFactory.
 */
@QuarkusTest
class Q06_A2AIdentityTest {

    @TestHTTPResource("/")
    URL base;

    @Test
    @Disabled("Blocked (issue #25): Agent Card 404. Missing beans were JSONRPCHandler and VertxSecurityHelper; CaseA2AWiring produces them but this was not run, because Quarkus augmentation stalls in ArC AutoAddScope bean discovery under -Pa2a on this host. See WP-007, In-process wiring.")
    void Q06_perCallBearerHeaderBecomesServerSideUser() throws Exception {
        Client client = A2ATestSupport.client(A2A.getAgentCard(base.toString()), false);
        try {
            ClientCallContext alice = new ClientCallContext(Map.of(),
                    Map.of("Authorization", "Bearer alice-token"));
            client.sendMessage(A2A.toUserMessage("who am I"), List.of(), err -> { }, alice);
            assertEquals("alice", CaseAgentExecutor.lastUser);

            ClientCallContext bob = new ClientCallContext(Map.of(),
                    Map.of("Authorization", "Bearer bob-token"));
            client.sendMessage(A2A.toUserMessage("who am I again"), List.of(), err -> { }, bob);
            assertEquals("bob", CaseAgentExecutor.lastUser);
        } finally {
            client.close();
        }
    }

    /**
     * Not provided: the SDK has no delegation grant or scope claim. TaskOperation is a fixed enum
     * (get, list, cancel, send, subscribe, push-config CRUD) and TaskAuthorizationProvider decides per
     * operation, so a child agent cannot be limited to a narrower subset of a parent's authority
     * through the protocol itself. Recorded from bytecode; to be built in the gateway (WP-204).
     */
    @Test
    @Disabled("Not provided by the A2A SDK: no delegation scope in the protocol or SDK. "
            + "Narrowing must be enforced by our gateway or authorization provider.")
    void Q06_childDelegationCanOnlyNarrowScope() {
    }
}
