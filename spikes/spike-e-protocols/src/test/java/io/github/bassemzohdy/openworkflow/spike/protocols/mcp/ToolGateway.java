package io.github.bassemzohdy.openworkflow.spike.protocols.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spike model of the tool gateway (WP-204): a decorator in front of an McpClient that applies
 * policy (deny by default), a per-invocation call budget, a timeout, and writes an audit event for
 * every decision. It wraps the McpClient interface with a dynamic proxy so every executeTool
 * overload passes through it, while listTools and other calls are delegated unchanged.
 */
public final class ToolGateway {

    public enum Decision { ALLOW, DENY, BUDGET_EXCEEDED }

    public enum Outcome { OK, TOOL_ERROR, TIMEOUT, TRANSPORT_ERROR, NOT_CALLED }

    public record AuditEvent(String correlationId, String principal, String tool,
                             Decision decision, Outcome outcome, long elapsedMillis) {}

    @FunctionalInterface
    public interface Policy {
        boolean allows(String principal, String tool);
    }

    private final McpClient delegate;
    private final Policy policy;
    private final Duration timeout;
    private final int budgetPerInvocation;
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();
    private final Map<UUID, AtomicInteger> usedByInvocation = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ToolGateway(McpClient delegate, Policy policy, Duration timeout, int budgetPerInvocation) {
        this.delegate = delegate;
        this.policy = policy;
        this.timeout = timeout;
        this.budgetPerInvocation = budgetPerInvocation;
    }

    /** The gated client an agent would be given instead of the raw McpClient. */
    public McpClient asMcpClient() {
        return (McpClient) Proxy.newProxyInstance(
                McpClient.class.getClassLoader(),
                new Class<?>[] {McpClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("executeTool") && args != null && args.length >= 1) {
                        ToolExecutionRequest request = (ToolExecutionRequest) args[0];
                        InvocationContext context = args.length == 2
                                ? (InvocationContext) args[1]
                                : InvocationContext.builder().build();
                        return execute(request, context);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    public List<AuditEvent> audit() {
        return List.copyOf(audit);
    }

    public ToolExecutionResult execute(ToolExecutionRequest request, InvocationContext context) {
        String principal = principalOf(context);
        String correlationId = String.valueOf(context.invocationId());
        String tool = request.name();
        long started = System.nanoTime();

        if (principal == null || !policy.allows(principal, tool)) {
            record(correlationId, principal, tool, Decision.DENY, Outcome.NOT_CALLED, started);
            return error("denied by policy: " + tool);
        }

        UUID invocation = context.invocationId();
        if (invocation == null) {
            // Fail closed: without an invocation id the per-run budget cannot be tracked.
            record(correlationId, principal, tool, Decision.DENY, Outcome.NOT_CALLED, started);
            return error("denied: no invocation id, budget cannot be tracked");
        }
        int used = usedByInvocation.computeIfAbsent(invocation, k -> new AtomicInteger()).incrementAndGet();
        if (used > budgetPerInvocation) {
            record(correlationId, principal, tool, Decision.BUDGET_EXCEEDED, Outcome.NOT_CALLED, started);
            return error("tool call budget exceeded: " + budgetPerInvocation);
        }

        Future<ToolExecutionResult> future = executor.submit(() -> delegate.executeTool(request, context));
        try {
            ToolExecutionResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            record(correlationId, principal, tool, Decision.ALLOW,
                    result.isError() ? Outcome.TOOL_ERROR : Outcome.OK, started);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            record(correlationId, principal, tool, Decision.ALLOW, Outcome.TIMEOUT, started);
            return error("timeout after " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            record(correlationId, principal, tool, Decision.ALLOW, Outcome.TRANSPORT_ERROR, started);
            return error("transport error: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record(correlationId, principal, tool, Decision.ALLOW, Outcome.TRANSPORT_ERROR, started);
            return error("interrupted");
        }
    }

    private void record(String correlationId, String principal, String tool,
                        Decision decision, Outcome outcome, long startedNanos) {
        audit.add(new AuditEvent(correlationId, principal, tool, decision, outcome,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)));
    }

    private static String principalOf(InvocationContext context) {
        InvocationParameters parameters = context.invocationParameters();
        if (parameters == null) {
            return null;
        }
        Object principal = parameters.get("principal");
        return principal == null ? null : principal.toString();
    }

    private static ToolExecutionResult error(String message) {
        return ToolExecutionResult.builder().isError(true).resultText(message).build();
    }

    public void close() {
        executor.shutdownNow();
    }
}
