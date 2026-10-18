package spike.d;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quarkus HTTP security policy that puts the Pdp in front of /tools/{tool}/call.
 * The authenticated identity supplies the agent and its scopes (roles named "scope:like:this").
 * Any exception on the way to a decision becomes DENY.
 */
@ApplicationScoped
public class ToolCallHttpPolicy implements HttpSecurityPolicy {

    public static final String NAME = "tool-call";
    public static final long BUDGET_FOR_SPIKE = 10L;

    @Inject
    Pdp pdp;

    /** Test switch: simulates a policy engine outage (throws before deciding). */
    @ConfigProperty(name = "spike.pdp.unavailable", defaultValue = "false")
    boolean unavailable;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext ctx, Uni<SecurityIdentity> identity,
                                            AuthorizationRequestContext requestContext) {
        return identity
                .map(id -> decide(ctx.normalizedPath(), id))
                .onFailure().recoverWithItem(CheckResult.DENY);
    }

    private CheckResult decide(String path, SecurityIdentity id) {
        if (id.isAnonymous()) {
            return CheckResult.DENY;
        }
        if (unavailable) {
            throw new IllegalStateException("simulated policy engine outage");
        }
        String tool = path.split("/")[2];
        Set<String> scopes = id.getRoles().stream()
                .filter(r -> r.contains(":"))
                .collect(Collectors.toSet());
        Pdp.Decision d = pdp.decide(new Pdp.Request(
                id.getPrincipal().getName(), "delegating-user", tool, scopes, BUDGET_FOR_SPIKE, false));
        return d.allowed() ? CheckResult.PERMIT : CheckResult.DENY;
    }
}
