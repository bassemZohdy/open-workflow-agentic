package io.github.bassemzohdy.openworkflow.agentic;

import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.LlmClient;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** Deterministic model for tests: no network, no API key. */
@Alternative
@Priority(1)
@ApplicationScoped
public class StubLlm implements LlmClient {
    @Override
    public String complete(String system, String user) {
        return "[stub] " + system.split("\\.")[0];
    }
}
