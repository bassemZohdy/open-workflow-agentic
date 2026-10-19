package io.github.bassemzohdy.openworkflow.agentic;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LlmClient;
import io.github.bassemzohdy.openworkflow.agentic.testkit.model.ScriptedLlmClient;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/**
 * Deterministic model for boot tests: no network, no API key. Delegates to the scripted double from oa-testkit;
 * with no script it answers with the first sentence of the system prompt.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class StubLlm implements LlmClient {

    private final ScriptedLlmClient model = ScriptedLlmClient.create()
            .fallback((system, user) -> "{\"text\":\"[stub] " + system.split("\\.")[0] + "\",\"actions\":[],\"escalation\":null}");

    @Override
    public String complete(String system, String user) {
        return model.complete(system, user);
    }
}
