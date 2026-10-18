package io.github.bassemzohdy.openworkflow.spike.protocols.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.http.McpParamHeader;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exactly two tools, served by the in-process Quarkiverse MCP server over streamable HTTP at /mcp.
 * Counters let tests prove whether a call reached the server (a gateway denial must not reach it).
 * The Authorization header of each call is recorded so identity tests can see what arrived.
 */
@ApplicationScoped
public class CaseTools {

    public static final AtomicInteger LOOKUPS = new AtomicInteger();
    public static final AtomicInteger DELETES = new AtomicInteger();
    public static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
    /** The Authorization header read straight from the HTTP request, independent of MCP param binding. */
    public static final AtomicReference<String> LAST_RAW_AUTHORIZATION = new AtomicReference<>();

    @Inject
    CurrentVertxRequest currentRequest;

    @Tool(name = "lookup_case", description = "Return the status of a case. Ids starting with SLOW take 3 seconds.")
    public String lookupCase(@ToolArg(name = "caseId", description = "Case identifier") String caseId,
                             @McpParamHeader("Authorization")
                             @ToolArg(name = "authorization", description = "Filled from the HTTP header", required = false)
                             String authorization)
            throws InterruptedException {
        LOOKUPS.incrementAndGet();
        LAST_AUTHORIZATION.set(authorization);
        try {
            LAST_RAW_AUTHORIZATION.set(currentRequest.getCurrent() == null ? "no-current-request"
                    : String.valueOf(currentRequest.getCurrent().request().getHeader("Authorization")));
        } catch (RuntimeException e) {
            LAST_RAW_AUTHORIZATION.set("error:" + e.getClass().getSimpleName());
        }
        if (caseId.startsWith("SLOW")) {
            Thread.sleep(3000);
        }
        return "case " + caseId + " status=OPEN";
    }

    @Tool(name = "delete_case", description = "Delete a case (destructive)")
    public String deleteCase(@ToolArg(name = "caseId", description = "Case identifier") String caseId) {
        DELETES.incrementAndGet();
        return "deleted " + caseId;
    }
}
