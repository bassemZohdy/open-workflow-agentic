package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The access seam of the audit API (M15). Authentication is not available yet (WP-102) and policy is not bound yet
 * (WP-103), so the rule is:
 * <ul>
 *   <li>If a {@link PolicyDecisionPoint} is bound, every audit request asks it; only ALLOW passes, anything else is
 *       refused. Null or failing decisions are refused too, because the port is fail closed.</li>
 *   <li>Otherwise the request is refused, unless {@code agentic.dev.audit-open=true}, a development-only setting that
 *       is false by default.</li>
 * </ul>
 * A refusal is a {@link ForbiddenException}, which becomes a 403 problem document.
 */
@Singleton
public class AuditAccess {

    /** Action name for reading audit events. */
    public static final String READ = "audit.events.read";
    /** Action name for verifying chains. */
    public static final String VERIFY = "audit.chains.verify";

    /** Until identity exists (WP-102), every audit caller is anonymous. */
    static final Subject ANONYMOUS = new Subject(SubjectKind.USER, "anonymous");

    private final PolicyDecisionPoint policy;
    private final boolean devOpen;

    @Inject
    public AuditAccess(Instance<PolicyDecisionPoint> policies,
                       @ConfigProperty(name = "agentic.dev.audit-open", defaultValue = "false") boolean devOpen) {
        this(policies.isResolvable() ? policies.get() : null, devOpen);
    }

    private AuditAccess(PolicyDecisionPoint policy, boolean devOpen) {
        this.policy = policy;
        this.devOpen = devOpen;
    }

    /** For tests and for wiring without CDI. {@code policy} may be null when no policy is bound. */
    public static AuditAccess of(PolicyDecisionPoint policy, boolean devOpen) {
        return new AuditAccess(policy, devOpen);
    }

    /** Refuses the request unless the action is allowed. */
    public void require(String action) {
        if (policy != null) {
            Decision decision = policy.decide(new AuthorizationRequest(ANONYMOUS, ActionKind.DATA_ACCESS, action, null));
            if (decision == null || !decision.allows()) {
                throw new ForbiddenException("audit access is denied");
            }
            return;
        }
        if (!devOpen) {
            throw new ForbiddenException("audit access is denied");
        }
    }
}
