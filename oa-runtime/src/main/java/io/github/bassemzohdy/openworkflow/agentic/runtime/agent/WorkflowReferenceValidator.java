package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Checks the symbol references paired with an OWS definition against the runtime registries. */
public final class WorkflowReferenceValidator {
    private WorkflowReferenceValidator() {}

    public record References(String workflow, Set<String> agents, Set<String> tools, Set<String> policies) {
        public References {
            if (workflow == null || workflow.isBlank()) throw new IllegalArgumentException("workflow is required");
            agents = Set.copyOf(Objects.requireNonNull(agents, "agents"));
            tools = Set.copyOf(Objects.requireNonNull(tools, "tools"));
            policies = Set.copyOf(Objects.requireNonNull(policies, "policies"));
        }
    }

    public static void validate(Collection<References> references, Set<String> workflows,
            Set<String> agents, Set<String> tools, Set<String> policies) {
        Set<String> seen = new HashSet<>();
        for (References ref : references) {
            Objects.requireNonNull(ref, "reference");
            if (!seen.add(ref.workflow())) throw new IllegalArgumentException("Duplicate workflow reference manifest: " + ref.workflow());
            requireKnown("workflow", ref.workflow(), workflows);
            ref.agents().forEach(name -> requireKnown("agent", name, agents));
            ref.tools().forEach(name -> requireKnown("tool", name, tools));
            ref.policies().forEach(name -> requireKnown("policy", name, policies));
        }
    }

    private static void requireKnown(String kind, String name, Set<String> known) {
        if (!known.contains(name)) throw new IllegalArgumentException("Unknown " + kind + " reference: " + name);
    }
}
