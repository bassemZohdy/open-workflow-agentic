package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.inject.Instance;
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
                            VerifiedToken token, ResourceCallBinding call) {
    }

    private final ResourceTokenGuard guard;
    private final ResourceCallBindings calls;
    private final IssuedTokenBindings issued;
    private final String audience;
    private final Clock clock;

    @Inject
    public AgentResourceAdmission(IdentitySettings settings, TokenVerifier verifier, DelegationGrants grants,
                                  IssuedTokenBindings bindings, javax.sql.DataSource data,
                                  Instance<ResourceCallBindings> resourceCalls) {
        this(settings.agentResourceAudience().map(audience -> new ResourceTokenGuard(verifier, audience,
                new JdbcReplayCache(data), grants, bindings, Clock.systemUTC(), 1)).orElse(null),
                resourceCalls.isResolvable() ? resourceCalls.get() : null, bindings,
                settings.agentResourceAudience().orElse(null), Clock.systemUTC());
    }

    /**
     * For tests and wiring without CDI. A null guard means the delegated path is not configured. Null
     * {@code calls} means no durable resource-call binding store exists: every delegated token is then refused with
     * {@link TokenRejectedException.Reason#BINDING_MISSING} (an unbound credential is never accepted).
     */
    public AgentResourceAdmission(ResourceTokenGuard guard, ResourceCallBindings calls, IssuedTokenBindings issued,
                                  String audience, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.guard = guard;
        this.calls = calls;
        this.issued = issued;
        this.audience = audience;
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
        ResourceCallBinding call = boundCall(token);
        return new Principal(token.tenant(), token.subject(), token.actor(), token.actors(), token.scopes(), token,
                call);
    }

    /**
     * The durable invocation attribution of this verified token, found by the VERIFIED issuer and token id only (never a
     * header or body field) and compared field by field. A missing binding, a disagreement or an unavailable store
     * refuses; nothing is defaulted.
     */
    private ResourceCallBinding boundCall(VerifiedToken token) {
        if (calls == null || issued == null) {
            throw new TokenRejectedException(TokenRejectedException.Reason.BINDING_MISSING);
        }
        java.util.Optional<ResourceCallBinding> found;
        java.util.Optional<IssuedTokenBindings.Binding> grantBinding;
        try {
            found = calls.find(token.issuer(), token.tokenId());
            grantBinding = issued.find(token.issuer(), token.tokenId());
        } catch (RuntimeException unavailable) {
            throw new TokenRejectedException(TokenRejectedException.Reason.VERIFIER_UNAVAILABLE, unavailable);
        }
        ResourceCallBinding call = found.orElseThrow(
                () -> new TokenRejectedException(TokenRejectedException.Reason.BINDING_MISSING));
        var scope = call.scope();
        boolean agrees = grantBinding.isPresent()
                && call.issuer().equals(token.issuer())
                && call.tokenId().equals(token.tokenId())
                && call.issuingGrantId().equals(grantBinding.get().grantId())
                && scope.tenant().equals(token.tenant())
                && scope.owner().equals(token.subject())
                && scope.audience().equals(audience)
                && scope.operation().equals(Pep.AGENT_RESEARCH_RUN)
                && call.issuedAt().equals(token.issuedAt())
                && call.expiresAt().equals(token.expiresAt());
        if (!agrees) {
            throw new TokenRejectedException(TokenRejectedException.Reason.BINDING_MISMATCH);
        }
        return call;
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
    public io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority authority(Principal principal) {
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
