package io.github.bassemzohdy.openworkflow.spike.protocols.a2a;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.apps.quarkus.CallContextFactory;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;

/**
 * Spike stand-in for real authentication: maps "Authorization: Bearer <user>-token" to an
 * AuthenticatedUser named <user>. A real deployment would validate a JWT here.
 */
@ApplicationScoped
public class BearerCallContextFactory implements CallContextFactory {

    @Override
    public ServerCallContext build(RoutingContext routingContext) {
        String header = routingContext.request().getHeader("Authorization");
        Map<String, Object> state = new HashMap<>();
        if (header != null && header.startsWith("Bearer ") && header.endsWith("-token")) {
            String username = header.substring("Bearer ".length(), header.length() - "-token".length());
            return new ServerCallContext(new AuthenticatedUser(username), state, Set.of());
        }
        return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, Set.of());
    }
}
