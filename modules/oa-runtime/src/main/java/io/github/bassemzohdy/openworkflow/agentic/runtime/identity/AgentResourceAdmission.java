package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Governed admission of a delegated token at the agent-run resource ({@code POST /agents/research}, WP-103). The generic
 * caller resolver refuses every token with an {@code act} claim (a delegated token is for a tool, not for the API); this
 * is the separate path for the one resource that a workflow step reaches on behalf of a user.
 *
 * <p>It is {@link ResourceTokenGuard} with this resource's exact settings, so every guarantee of ADR 0021 applies: the
 * token names this resource's audience and no other ({@code agentic.identity.agent-resource-audience}), it is delegated,
 * lives at most 300 seconds, holds {@link #SCOPE}, has a durable binding to the grant that issued it, that grant and every
 * ancestor are active now and match the token's user, tenant, actor chain and scopes, and it is used once (a replay is
 * refused). Without the audience setting nothing is admitted.
 *
 * <p>Mapping the resource relies on (docs/handoffs/claude/WP-103.md): tenant = the token's tenant claim; user = the
 * token's subject, the person on whose behalf the call is made; actor = the nearest entry of the delegation chain; scopes
 * = the token's scopes. Nothing is read from request headers or the body.
 */
@ApplicationScoped
public class AgentResourceAdmission {

    /** The scope a delegated token needs for the agent-run resource. */
    public static final String SCOPE = "oa.agent.run";

    /** Who a delegated call is for and who makes it. */
    public record Principal(String tenant, Subject user, String actor, List<String> actors, Set<String> scopes,
                            VerifiedToken token) {
    }

    private final ResourceTokenGuard guard;

    @Inject
    public AgentResourceAdmission(IdentitySettings settings, TokenVerifier verifier, DelegationGrants grants,
                                  IssuedTokenBindings bindings, javax.sql.DataSource data) {
        this(settings.agentResourceAudience().map(audience -> new ResourceTokenGuard(verifier, audience,
                new JdbcReplayCache(data), grants, bindings, Clock.systemUTC(), 1)).orElse(null));
    }

    /** For tests and wiring without CDI. A null guard means the delegated path is not configured. */
    public AgentResourceAdmission(ResourceTokenGuard guard) {
        this.guard = guard;
    }

    /** True when a resource audience is configured, so delegated tokens can be admitted at all. */
    public boolean configured() {
        return guard != null;
    }

    /**
     * Admits the token or throws.
     *
     * @throws TokenRejectedException the token is refused; {@code reason()} says why (also when not configured)
     */
    public Principal admit(String bearer) {
        if (guard == null) {
            throw new TokenRejectedException(TokenRejectedException.Reason.AUDIENCE);
        }
        VerifiedToken token = guard.admit(Objects.requireNonNull(bearer, "bearer"), Set.of(SCOPE));
        return new Principal(token.tenant(), token.subject(), token.actor(), token.actors(), token.scopes(), token);
    }

    /**
     * Re-checks an admitted principal's signed expiry and exact issuing grant chain, without consuming the single use
     * again. The resource calls it after its policy and audit work, immediately before it acts.
     *
     * @throws TokenRejectedException the authority is gone
     */
    public void revalidate(Principal principal) {
        if (guard == null) {
            throw new TokenRejectedException(TokenRejectedException.Reason.AUDIENCE);
        }
        guard.revalidate(principal.token(), Set.of(SCOPE));
    }

    /**
     * The admitted principal as a transient authority handle: its {@code requireCurrent} re-checks the signed expiry and
     * the exact grant chain with this resource's settings and the guard's clock, without a second replay admission.
     */
    public io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority authority(Principal principal,
            Clock clock) {
        Objects.requireNonNull(principal, "principal");
        return io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority.ofDelegatedToken(
                principal.token(), clock, (token, scopes) -> {
                    if (guard == null) {
                        throw new TokenRejectedException(TokenRejectedException.Reason.AUDIENCE);
                    }
                    guard.revalidate(token, scopes);
                });
    }
}
