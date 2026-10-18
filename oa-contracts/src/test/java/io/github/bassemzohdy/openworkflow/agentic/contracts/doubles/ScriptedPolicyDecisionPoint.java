package io.github.bassemzohdy.openworkflow.agentic.contracts.doubles;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A policy decision point whose behaviour a test scripts: a fixed decision, a throw, a null or a slow answer. */
public final class ScriptedPolicyDecisionPoint implements PolicyDecisionPoint {

    private final Function<AuthorizationRequest, Decision> behaviour;
    private final List<AuthorizationRequest> received = new ArrayList<>();

    public ScriptedPolicyDecisionPoint(Function<AuthorizationRequest, Decision> behaviour) {
        this.behaviour = behaviour;
    }

    @Override
    public synchronized Decision decide(AuthorizationRequest request) {
        received.add(request);
        return behaviour.apply(request);
    }

    /** Requests received so far. */
    public synchronized List<AuthorizationRequest> received() {
        return List.copyOf(received);
    }
}
