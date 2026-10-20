package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The access seam of the audit API (M15, WP-102). Every request carries the {@link CallerContext} that
 * {@code CallerContextResolver} established: a verified bearer token, or, in a development or test profile only, the
 * trusted development headers. There is no anonymous caller. The rule is:
 * <ul>
 *   <li>A caller that was built by hand ({@link CallerContext.Provenance#UNVERIFIED}) or is missing is refused. A
 *       request can never produce one; this is a guard against in-process misuse.</li>
 *   <li>If a {@link PolicyDecisionPoint} is bound, the policy is asked about the caller's own subject, with the
 *       caller's scopes as context. Only ALLOW passes; anything else is refused. Null or failing decisions are refused
 *       too, because the port is fail closed. A caller whose scopes cannot form a valid policy context is refused.</li>
 *   <li>Otherwise, with {@code agentic.dev.audit-open=true}, a development-only setting that is false by default, any
 *       authenticated caller passes: it removes the policy requirement only.</li>
 *   <li>Otherwise the shared policy enforcement point decides ({@link Pep#auditAccess}: audited, bounded; needs the scope
 *       {@code oa.audit.read} or {@code oa.audit.verify}). DENY is 403, an unavailable decision is 503 and nothing is
 *       read. With no enforcement point the request is refused.</li>
 * </ul>
 * A refusal is a {@link ForbiddenException}, which becomes a 403 problem document. A request without a usable
 * credential never reaches this class: the resolver answers 401 first.
 *
 * <p>Audit reads are not yet tenant-filtered (WP-404).
 */
@Singleton
public class AuditAccess {

    /** Action name for reading audit events. */
    public static final String READ = "audit.events.read";
    /** Action name for verifying chains. */
    public static final String VERIFY = "audit.chains.verify";

    private final PolicyDecisionPoint policy;
    private final java.util.function.Supplier<Pep> pep;
    private final boolean devOpen;

    @Inject
    public AuditAccess(Instance<PolicyDecisionPoint> policies, Instance<Pep> peps,
                       @ConfigProperty(name = "agentic.dev.audit-open", defaultValue = "false") boolean devOpen) {
        // the enforcement point is resolved on first use, so a deployment that never reaches it does not build its engine
        this(policies.isResolvable() ? policies.get() : null,
                () -> peps.isResolvable() ? peps.get() : null, devOpen);
    }

    private AuditAccess(PolicyDecisionPoint policy, java.util.function.Supplier<Pep> pep, boolean devOpen) {
        this.policy = policy;
        this.pep = pep;
        this.devOpen = devOpen;
    }

    /** For tests and for wiring without CDI. {@code policy} may be null when no policy is bound. */
    public static AuditAccess of(PolicyDecisionPoint policy, boolean devOpen) {
        return new AuditAccess(policy, () -> null, devOpen);
    }

    /** With the shared enforcement point (WP-103). */
    public static AuditAccess of(PolicyDecisionPoint policy, Pep pep, boolean devOpen) {
        return new AuditAccess(policy, () -> pep, devOpen);
    }

    /**
     * Refuses the request unless the caller may perform the action.
     *
     * @param action one of {@link #READ} and {@link #VERIFY}
     * @param caller the caller of the request, as the resolver established it
     * @throws ForbiddenException when the caller is not allowed, whatever the reason
     */
    public void require(String action, CallerContext caller) {
        if (caller == null || caller.provenance() == CallerContext.Provenance.UNVERIFIED || caller.subject() == null) {
            throw denied();
        }
        if (policy != null) {
            Decision decision = policy.decide(request(action, caller));
            if (decision == null || !decision.allows()) {
                throw denied();
            }
            return;
        }
        if (devOpen) {
            return;
        }
        Pep enforcement = pep.get();
        if (enforcement != null) {
            Decision decision;
            try {
                decision = enforcement.auditAccess(caller.subject(), scopesOf(caller), action);
            } catch (IllegalArgumentException invalidScopes) {
                throw denied(); // scopes without the policy-context syntax cannot be put in front of a policy
            } catch (RuntimeException unavailable) {
                throw unavailable();
            }
            if (decision == null || decision.outcome() == DecisionOutcome.ERROR) {
                throw unavailable();
            }
            if (!decision.allows()) {
                throw denied();
            }
            return;
        }
        throw denied();
    }

    private static java.util.Set<String> scopesOf(CallerContext caller) {
        return caller.scopes();
    }

    private static jakarta.ws.rs.WebApplicationException unavailable() {
        return new io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException(
                jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE,
                "the policy decision is unavailable; nothing was read");
    }

    private static AuthorizationRequest request(String action, CallerContext caller) {
        try {
            return new AuthorizationRequest(caller.subject(), ActionKind.DATA_ACCESS, action, null,
                    new PolicyContext(caller.scopes(), null, null, null, null, null));
        } catch (IllegalArgumentException invalidScopes) {
            // Scopes that do not have the policy-context syntax cannot be put in front of a policy. Refuse, do not 500.
            throw denied();
        }
    }

    private static ForbiddenException denied() {
        return new ForbiddenException("audit access is denied");
    }
}
