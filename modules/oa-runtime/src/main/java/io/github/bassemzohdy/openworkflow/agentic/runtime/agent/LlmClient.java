package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

/** Narrow model port so the agent graph can be tested without a live model. */
public interface LlmClient {
    String complete(String system, String user);

    /**
     * Fixed model this provider instance actually addresses for every call, or null if it cannot prove one.
     * The value must be derived from the built transport configuration, not caller input, and remain stable.
     */
    default String configuredModelId() { return null; }
}
