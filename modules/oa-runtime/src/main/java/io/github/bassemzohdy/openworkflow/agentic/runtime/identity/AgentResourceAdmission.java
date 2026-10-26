package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Why a witness was refused. Carries no token, claim or scope value. */
    public static final class WitnessRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        WitnessRefusedException(String reason) {
            super("admission witness refused: " + reason);
        }
    }

    /**
     * Opaque, non-serializable evidence that THIS admission instance verified a bearer and found its exact durable
     * resource-call binding. Only {@link #admitCall} creates one (the constructor is private to this class and no
     * factory takes a {@link Principal}, {@link VerifiedToken}, binding or scope as proof). It holds no bearer and
     * exposes no token, claim or freshness: it is admission/attribution evidence, not a current permission, a callback
     * acknowledgement, a ResourceExecutionFence or a graph capability. Never put it in graph/workflow state, config or
     * checkpoint metadata, or a ThreadLocal.
     */
    public static final class AdmittedCall {
        private final AgentResourceAdmission minter;
        private final VerifiedToken token;
        private final ResourceCallBinding call;
        private final AtomicBoolean bound = new AtomicBoolean();

        private AdmittedCall(AgentResourceAdmission minter, VerifiedToken token, ResourceCallBinding call) {
            this.minter = minter;
            this.token = token;
            this.call = call;
        }

        /** Historical attribution data of the call (not authority). */
        public ResourceCallScope scope() {
            return call.scope();
        }

        public String callKey() {
            return call.scope().key();
        }

        @Override
        public String toString() {
            return "AdmittedCall[redacted]";
        }
    }

    /**
     * An {@link AdmittedCall} bound once to the exact backend-owned {@link ResourceExecutionEntry}. Still only
     * admission/attribution evidence: it does not say the entry is fresh (the durable gate owns NO_ENTRY -> ENTERED and
     * dispatch) and is not permission to run; a later live fence must re-read grants, bindings and policy.
     */
    public static final class BoundCall {
        private final AdmittedCall admitted;
        private final ResourceExecutionEntry entry;

        private BoundCall(AdmittedCall admitted, ResourceExecutionEntry entry) {
            this.admitted = admitted;
            this.entry = entry;
        }

        /** The exact retained entry (data). */
        public ResourceExecutionEntry entry() {
            return entry;
        }

        /**
         * Requires that this witness is still open and unexpired and retains exactly {@code expected} (full scope and
         * epoch). Does not consult grants, policy or the replay store.
         */
        public ResourceExecutionEntry requireEntry(ResourceExecutionEntry expected) {
            admitted.minter.requireOpen(admitted);
            if (!entry.equals(Objects.requireNonNull(expected, "expected"))) {
                throw new WitnessRefusedException("entry differs from the retained one");
            }
            return entry;
        }

        @Override
        public String toString() {
            return "BoundCall[redacted]";
        }
    }

    private final ResourceTokenGuard guard;
    private final ResourceCallBindings calls;
    private final IssuedTokenBindings issued;
    private final String audience;
    private final Clock clock;
    private volatile boolean closed;

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
     * Admits the bearer exactly like {@link #admit} (one replay use) and returns opaque evidence instead of data. The
     * signed expiry and the exact grant chain are re-checked after the blocking binding lookups, without a second replay
     * use, so a revocation or expiry that happened during the lookup refuses.
     *
     * @throws TokenRejectedException the token is refused
     * @throws WitnessRefusedException this admission is closed
     */
    public AdmittedCall admitCall(String bearer) {
        requireNotClosed();
        if (guard == null) {
            throw new TokenRejectedException(TokenRejectedException.Reason.AUDIENCE);
        }
        VerifiedToken token = guard.admit(Objects.requireNonNull(bearer, "bearer"), Set.of(SCOPE));
        ResourceCallBinding call = boundCall(token);
        guard.revalidate(token, Set.of(SCOPE));
        requireNotClosed();
        return new AdmittedCall(this, token, call);
    }

    /**
     * Binds the witness once to {@code entry}, which the backend (not the caller of this API) owns: its full scope must
     * equal the admitted call's scope. A different admission instance, a closed admission, an expired credential, a
     * different scope (also with the same call key) or a second bind refuses. Does not repeat admission, consume replay
     * or establish that the entry is fresh.
     */
    public BoundCall bind(AdmittedCall admitted, ResourceExecutionEntry entry) {
        Objects.requireNonNull(admitted, "admitted");
        Objects.requireNonNull(entry, "entry");
        requireOpen(admitted);
        if (!admitted.call.scope().equals(entry.scope())) {
            throw new WitnessRefusedException("entry scope differs from the admitted call");
        }
        if (!admitted.bound.compareAndSet(false, true)) {
            throw new WitnessRefusedException("already bound");
        }
        requireOpen(admitted);
        return new BoundCall(admitted, entry);
    }

    /** Invalidates every witness of this admission at once. */
    @PreDestroy
    public void close() {
        closed = true;
    }

    private void requireNotClosed() {
        if (closed) {
            throw new WitnessRefusedException("admission closed");
        }
    }

    private void requireOpen(AdmittedCall admitted) {
        if (admitted.minter != this) {
            throw new WitnessRefusedException("minted by another admission");
        }
        requireNotClosed();
        if (!admitted.token.expiresAt().isAfter(clock.instant())) {
            throw new WitnessRefusedException("credential expired");
        }
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
