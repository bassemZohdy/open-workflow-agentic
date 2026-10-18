package io.github.bassemzohdy.openworkflow.spike.protocols.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WP-007 question 3: how the MCP client attaches a caller identity to each call.
 * The langchain4j client exposes McpHeadersSupplier, a function of the per-call context. It is
 * used here to derive the Authorization header from the invocation's own parameters, so each call
 * can carry its own token. No token exchange is performed: the tokens are literal test values.
 */
@QuarkusTest
class Q03_McpCallIdentityTest {

    @TestHTTPResource("/mcp")
    URL mcpUrl;

    static final java.util.concurrent.atomic.AtomicInteger HEADER_CALLS = new java.util.concurrent.atomic.AtomicInteger();

    /** Per-call header derivation: the bearer comes from the invocation, not from client config. */
    private McpClient clientWithPerCallAuthorization(String key) {
        var transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpUrl.toString())
                .timeout(Duration.ofSeconds(10))
                .customHeaders(callContext -> {
                    HEADER_CALLS.incrementAndGet();
                    // Initialization and listTools run without an invocation context; send no identity then.
                    var invocation = callContext.invocationContext();
                    if (invocation == null || invocation.invocationParameters() == null) {
                        return Map.of();
                    }
                    Object bearer = invocation.invocationParameters().get("bearer");
                    return bearer == null ? Map.of() : Map.of("Authorization", "Bearer " + bearer);
                })
                .build();
        return new DefaultMcpClient.Builder()
                .key(key)
                .clientName("spike-e-protocols")
                .clientVersion("0.1.0")
                .transport(transport)
                .build();
    }

    private static InvocationContext withBearer(String token) {
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .invocationParameters(InvocationParameters.from("bearer", token))
                .build();
    }

    private static ToolExecutionRequest lookup(String caseId) {
        return ToolExecutionRequest.builder()
                .id("q03-" + System.nanoTime())
                .name("lookup_case")
                .arguments("{\"caseId\":\"" + caseId + "\"}")
                .build();
    }

    /**
     * Verified: the per-call Authorization header from McpHeadersSupplier reaches the HTTP request
     * of each tools/call. Read through CurrentVertxRequest, not through the MCP parameter binding.
     */
    @Test
    void Q03_perCallAuthorizationHeaderReachesServerForEachCall() throws Exception {
        try (McpClient client = clientWithPerCallAuthorization("identity-per-call")) {
            client.executeTool(lookup("C-10"), withBearer("token-alice"));
            assertEquals("Bearer token-alice", CaseTools.LAST_RAW_AUTHORIZATION.get());

            client.executeTool(lookup("C-11"), withBearer("token-bob"));
            assertEquals("Bearer token-bob", CaseTools.LAST_RAW_AUTHORIZATION.get());
        }
    }

    /**
     * Not working in this spike: the Quarkiverse @McpParamHeader tool parameter stayed null while the
     * raw request carried the header (see the test above). Cause not established; the binding may
     * need configuration or may not populate on this path. Kept @Disabled with the observed failure.
     */
    @Test
    @Disabled("Observed: @McpParamHeader(\"Authorization\") tool parameter is null while the raw HTTP "
            + "header is present. Cause not established in WP-007.")
    void Q03_mcpParamHeaderBindingPopulatesToolParameter() throws Exception {
        try (McpClient client = clientWithPerCallAuthorization("identity-param-binding")) {
            client.executeTool(lookup("C-12"), withBearer("token-carol"));
            assertEquals("Bearer token-carol", CaseTools.LAST_AUTHORIZATION.get());
        }
    }

    /**
     * Mechanism check for a per-user exchanged token: a fake exchange issues a narrowed token for
     * each call. This proves the transport can send a token derived per call; it does not prove
     * any IdP performs RFC 8693 exchange.
     */
    @Test
    void Q03_exchangedNarrowTokenCanBeDerivedPerCall() throws Exception {
        Map<String, String> fakeExchange = Map.of(
                "alice", "narrow:lookup_case:alice:exp=60s",
                "bob", "narrow:lookup_case:bob:exp=60s");
        try (McpClient client = clientWithPerCallAuthorization("identity-exchange")) {
            InvocationContext alice = withBearer(fakeExchange.get("alice"));
            ToolExecutionRequest request = lookup("C-13");
            assertFalse(client.executeTool(request, alice).isError());
            assertEquals("Bearer narrow:lookup_case:alice:exp=60s", CaseTools.LAST_RAW_AUTHORIZATION.get());
        }
    }

    /**
     * The OIDC MCP auth provider (quarkus-langchain4j-oidc-mcp-auth-provider 1.14.2) reads the
     * current request's TokenCredential from ArC and sends it as a Bearer header. That is inbound
     * token passthrough, not exchange. Running it needs quarkus-oidc and an IdP, not available in
     * this sandbox, so the behaviour is recorded from bytecode only.
     */
    @Test
    @Disabled("Needs quarkus-oidc and an identity provider; not available in the spike sandbox. "
            + "Read in bytecode: OidcMcpAuthProvider resolves TokenCredential from the request context.")
    void Q03_oidcProviderSendsInboundRequestTokenNotExchangedToken() {
    }
}
