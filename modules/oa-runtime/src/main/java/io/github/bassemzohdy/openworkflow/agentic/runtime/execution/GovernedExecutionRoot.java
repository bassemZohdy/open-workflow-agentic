package io.github.bassemzohdy.openworkflow.agentic.runtime.execution;

import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.LiveIdentityResourceFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.LiveResearchPolicyFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.CompositeExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResourceTaskExecutionFence;
import java.util.Objects;

/**
 * Explicit candidate composition boundary. This is not a CDI bean or route and does not create a gate or dispatch
 * work. A future production root must establish trusted task, target, owner and audit delegates before activation.
 */
public final class GovernedExecutionRoot {
    private GovernedExecutionRoot() { }

    public static JdbcResourceExecutionGate.CredentialFence pair(ResourceCallBinding binding,
            LiveIdentityResourceFence identity, LiveResearchPolicyFence policy, ResourceTaskExecutionFence task,
            CompositeExecutionFence composite) {
        Objects.requireNonNull(identity, "identity").requirePairing(binding, composite, policy, task);
        return JdbcResourceExecutionGate.CredentialFence.pair(binding, composite);
    }
}
