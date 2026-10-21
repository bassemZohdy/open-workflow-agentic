package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

/**
 * Thrown before a provider is reached when a model call has no approved context. A policy refusal is
 * {@link io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied}; this is the case where no policy
 * question can even be put because there is no approved task to put it about.
 */
public final class ModelCallRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModelCallRefused(String message) {
        super(message);
    }
}
