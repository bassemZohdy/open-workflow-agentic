package io.github.bassemzohdy.openworkflow.spike.protocols.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.github.bassemzohdy.openworkflow.spike.protocols.mcp.ToolGateway.AuditEvent;
import io.github.bassemzohdy.openworkflow.spike.protocols.mcp.ToolGateway.Decision;
import io.github.bassemzohdy.openworkflow.spike.protocols.mcp.ToolGateway.Outcome;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * WP-007 question 2: an in-process MCP server (two tools, streamable HTTP) called through an
 * interceptor that enforces policy, audit, timeout and a per-call budget.
 */
@QuarkusTest
class Q02_McpGatewayTest {

    @TestHTTPResource("/mcp")
    URL mcpUrl;

    private McpClient rawClient(String key, Duration nativeToolTimeout, McpClientListener... listeners) {
        var transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpUrl.toString())
                .timeout(Duration.ofSeconds(10))
                .build();
        var builder = new DefaultMcpClient.Builder()
                .key(key)
                .clientName("spike-e-protocols")
                .clientVersion("0.1.0")
                .transport(transport)
                .toolExecutionTimeout(nativeToolTimeout);
        for (McpClientListener listener : listeners) {
            builder.addListener(listener);
        }
        return builder.build();
    }

    private static ToolExecutionRequest call(String tool, String caseId) {
        return ToolExecutionRequest.builder()
                .id("req-" + System.nanoTime())
                .name(tool)
                .arguments("{\"caseId\":\"" + caseId + "\"}")
                .build();
    }

    private static InvocationContext as(String principal) {
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .invocationParameters(InvocationParameters.from("principal", principal))
                .build();
    }

    private static AuditEvent last(ToolGateway gateway) {
        List<AuditEvent> events = gateway.audit();
        return events.get(events.size() - 1);
    }

    private static final ToolGateway.Policy LOOKUP_ONLY_FOR_ALICE =
            (principal, tool) -> "alice".equals(principal) && "lookup_case".equals(tool);

    private static final ToolGateway.Policy ALLOW_ALL = (principal, tool) -> true;

    @Test
    void Q02_serverExposesTwoToolsOverStreamableHttp() throws Exception {
        try (McpClient client = rawClient("cases-raw", Duration.ofSeconds(10))) {
            List<ToolSpecification> tools = client.listTools();
            Set<String> names = tools.stream().map(ToolSpecification::name).collect(Collectors.toSet());
            assertEquals(Set.of("lookup_case", "delete_case"), names);

            ToolExecutionResult result = client.executeTool(call("lookup_case", "C-1"));
            assertFalse(result.isError());
            assertTrue(result.resultText().contains("C-1"), result.resultText());
        }
    }

    @Test
    void Q02_gatewayAllowsPermittedToolAndAuditsDecision() throws Exception {
        try (McpClient raw = rawClient("cases-allow", Duration.ofSeconds(10))) {
            ToolGateway gateway = new ToolGateway(raw, LOOKUP_ONLY_FOR_ALICE, Duration.ofSeconds(5), 5);
            McpClient gated = gateway.asMcpClient();
            InvocationContext ctx = as("alice");

            ToolExecutionResult result = gated.executeTool(call("lookup_case", "C-2"), ctx);

            assertFalse(result.isError(), result.resultText());
            AuditEvent event = last(gateway);
            assertEquals(Decision.ALLOW, event.decision());
            assertEquals(Outcome.OK, event.outcome());
            assertEquals("alice", event.principal());
            assertEquals(String.valueOf(ctx.invocationId()), event.correlationId());
            gateway.close();
        }
    }

    @Test
    void Q02_gatewayDeniesByPolicyBeforeTheServerSeesTheCall() throws Exception {
        try (McpClient raw = rawClient("cases-deny", Duration.ofSeconds(10))) {
            ToolGateway gateway = new ToolGateway(raw, LOOKUP_ONLY_FOR_ALICE, Duration.ofSeconds(5), 5);
            McpClient gated = gateway.asMcpClient();
            int deletesBefore = CaseTools.DELETES.get();

            ToolExecutionResult result = gated.executeTool(call("delete_case", "C-3"), as("alice"));

            assertTrue(result.isError());
            assertEquals(deletesBefore, CaseTools.DELETES.get(), "denied call must not reach the server");
            AuditEvent event = last(gateway);
            assertEquals(Decision.DENY, event.decision());
            assertEquals(Outcome.NOT_CALLED, event.outcome());
            gateway.close();
        }
    }

    @Test
    void Q02_gatewayDeniesCallWithoutPrincipal() throws Exception {
        try (McpClient raw = rawClient("cases-anon", Duration.ofSeconds(10))) {
            ToolGateway gateway = new ToolGateway(raw, ALLOW_ALL, Duration.ofSeconds(5), 5);
            ToolExecutionResult result = gateway.asMcpClient()
                    .executeTool(call("lookup_case", "C-4"), InvocationContext.builder().build());
            assertTrue(result.isError());
            assertEquals(Decision.DENY, last(gateway).decision());
            gateway.close();
        }
    }

    @Test
    void Q02_gatewayEnforcesTimeoutAndAudits() throws Exception {
        try (McpClient raw = rawClient("cases-timeout", Duration.ofSeconds(10))) {
            ToolGateway gateway = new ToolGateway(raw, ALLOW_ALL, Duration.ofMillis(300), 5);
            long started = System.nanoTime();

            ToolExecutionResult result = gateway.asMcpClient()
                    .executeTool(call("lookup_case", "SLOW-1"), as("alice"));

            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(result.isError());
            assertTrue(result.resultText().startsWith("timeout"), result.resultText());
            assertTrue(elapsedMillis < 2000, "gateway must return before the 3 s server delay: " + elapsedMillis);
            assertEquals(Outcome.TIMEOUT, last(gateway).outcome());
            gateway.close();
        }
    }

    @Test
    void Q02_gatewayEnforcesPerInvocationBudget() throws Exception {
        try (McpClient raw = rawClient("cases-budget", Duration.ofSeconds(10))) {
            ToolGateway gateway = new ToolGateway(raw, ALLOW_ALL, Duration.ofSeconds(5), 2);
            McpClient gated = gateway.asMcpClient();
            InvocationContext run = as("alice");
            int lookupsBefore = CaseTools.LOOKUPS.get();

            assertFalse(gated.executeTool(call("lookup_case", "C-5"), run).isError());
            assertFalse(gated.executeTool(call("lookup_case", "C-6"), run).isError());
            ToolExecutionResult third = gated.executeTool(call("lookup_case", "C-7"), run);

            assertTrue(third.isError());
            assertEquals(Decision.BUDGET_EXCEEDED, last(gateway).decision());
            assertEquals(lookupsBefore + 2, CaseTools.LOOKUPS.get(), "third call must not reach the server");

            // A new invocation has its own budget.
            assertFalse(gated.executeTool(call("lookup_case", "C-8"), as("alice")).isError());
            gateway.close();
        }
    }

    /**
     * Characterization (finding): a native McpClientListener cannot deny a tool call. A throwing
     * beforeExecuteTool does not stop the request; the server still runs the tool.
     */
    @Test
    void Q02_nativeListenerCannotDenyToolCall() throws Exception {
        McpClientListener denyingListener = new McpClientListener() {
            @Override
            public void beforeExecuteTool(McpCallContext context) {
                throw new IllegalStateException("listener policy: deny");
            }
        };
        try (McpClient client = rawClient("cases-listener", Duration.ofSeconds(10), denyingListener)) {
            int lookupsBefore = CaseTools.LOOKUPS.get();
            ToolExecutionResult result = client.executeTool(call("lookup_case", "C-9"));
            assertFalse(result.isError(), "listener exception did not produce an error result");
            assertEquals(lookupsBefore + 1, CaseTools.LOOKUPS.get(), "the server still ran the tool");
        }
    }

    /**
     * Characterization (finding): the native toolExecutionTimeout bounds the wait, but the timeout
     * comes back as an ordinary result with isError=false, so a caller that checks only the flag
     * records a timeout as success. A gateway must classify this itself.
     */
    @Test
    void Q02_nativeToolExecutionTimeoutReturnsNonErrorText() throws Exception {
        try (McpClient client = rawClient("cases-native-timeout", Duration.ofMillis(300))) {
            long started = System.nanoTime();
            ToolExecutionResult result = client.executeTool(call("lookup_case", "SLOW-2"));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertFalse(result.isError(), "native timeout is not flagged as isError");
            assertTrue(result.resultText().toLowerCase().contains("timeout"), result.resultText());
            assertTrue(elapsedMillis < 2500, "native timeout must bound the call: " + elapsedMillis);
        }
    }
}
