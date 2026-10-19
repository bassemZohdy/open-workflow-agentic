package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/** Escalations are allowed only by an audited PEP decision; absent PEP wiring denies them. */
@ApplicationScoped
public class PepEscalationAuthorizer implements EscalationAuthorizer {
    private static final Subject AGENT = new Subject(SubjectKind.AGENT, "research-agent");

    @Inject
    Instance<Pep> pep;

    @Override
    public boolean isAuthorized(String target) {
        if (pep.isUnsatisfied()) {
            return false;
        }
        var request = new AuthorizationRequest(AGENT, ActionKind.DELEGATE, "agent:" + target, null,
                PolicyContext.EMPTY);
        return pep.get().decide(request).allows();
    }
}
