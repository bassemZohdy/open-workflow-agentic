package io.github.bassemzohdy.openworkflow.agentic.agent;

/** Narrow model port so the agent graph can be tested without a live model. */
public interface LlmClient {
    String complete(String system, String user);
}
