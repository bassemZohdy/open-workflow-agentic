package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

/** Authorization boundary for model-proposed escalation. Implementations must fail closed. */
@FunctionalInterface
public interface EscalationAuthorizer {
    boolean isAuthorized(String target);
}
