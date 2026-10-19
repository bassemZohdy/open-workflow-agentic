package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.server.PublicAgentCard;
import org.a2aproject.sdk.server.common.quarkus.VertxSecurityHelper;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;

/**
 * Wiring the A2A reference JSON-RPC routes need in a Quarkus app. The reference jar declares
 * A2AServerRoutes as a singleton with an @Observes Router method, but JSONRPCHandler and
 * VertxSecurityHelper carry no bean-defining annotation, so they are not discovered and must be produced.
 */
@ApplicationScoped
public class CaseA2AWiring {

    @Produces
    @Singleton
    JSONRPCHandler jsonRpcHandler(@PublicAgentCard AgentCard card, RequestHandler requestHandler, Executor executor) {
        return new JSONRPCHandler(card, requestHandler, executor);
    }

    @Produces
    @Singleton
    VertxSecurityHelper vertxSecurityHelper() {
        return new VertxSecurityHelper();
    }

    @Produces
    @Singleton
    Executor a2aExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
