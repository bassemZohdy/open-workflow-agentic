package io.github.bassemzohdy.openworkflow.agentic.runtime.agent.fixture;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LlmClient;

/** Fixture for the model-boundary rule: reaches the provider port directly, which the rule must flag. */
public final class BypassingCaller {

    private final LlmClient provider;

    public BypassingCaller(LlmClient provider) {
        this.provider = provider;
    }

    public String ask(String question) {
        return provider.complete("system", question);
    }
}
