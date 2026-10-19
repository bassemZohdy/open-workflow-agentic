package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

/** Kinds of versioned artifact that change system behaviour and therefore sit in the registry (M09, M10, plan 2). */
public enum ArtifactKind {
    WORKFLOW_DEFINITION,
    AGENT_GRAPH,
    PROMPT,
    POLICY_BUNDLE,
    TOOL_CONTRACT,
    MODEL_BINDING
}
