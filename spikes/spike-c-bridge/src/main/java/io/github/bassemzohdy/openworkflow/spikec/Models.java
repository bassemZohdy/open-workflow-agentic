package io.github.bassemzohdy.openworkflow.spikec;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LlmClient;
import io.github.bassemzohdy.openworkflow.agentic.testkit.model.ScriptedLlmClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** The model is the scripted double from oa-testkit: deterministic, no network. Answers come from the fallback. */
@ApplicationScoped
public class Models {

    @Produces
    @ApplicationScoped
    public LlmClient llm() {
        return ScriptedLlmClient.create()
                .fallback((system, user) -> "Brief (" + system.length() + " chars of instruction): " + user.replace('\n', ' '));
    }
}
