package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versions that were in force when an event happened (M15, M09, M10). Every field is optional: a null field is not
 * written. Stamps are stored as {@code stamp.*} attributes, so they are hashed with the event: a changed model or
 * policy version is detected by {@code ChainVerifier}.
 *
 * @param model      model binding version, for example {@code gpt-4o-mini@2026-10-01}
 * @param prompt     prompt version
 * @param tool       tool contract version
 * @param policy     stable id of the deciding policy
 * @param policySet  version of the policy set that contained the policy (from the {@link Decision})
 * @param definition workflow definition version
 */
public record VersionStamps(String model, String prompt, String tool, String policy, String policySet,
                            String definition) {

    public static final int MAX_LENGTH = 256;

    /** No stamps. */
    public static final VersionStamps NONE = new VersionStamps(null, null, null, null, null, null);

    public VersionStamps {
        model = optional("model", model);
        prompt = optional("prompt", prompt);
        tool = optional("tool", tool);
        policy = optional("policy", policy);
        policySet = optional("policySet", policySet);
        definition = optional("definition", definition);
    }

    /** The policy stamps taken from a decision: the deciding policy id and its policy-set version. */
    public static VersionStamps ofDecision(Decision decision) {
        return new VersionStamps(null, null, null, decision.policy().policyId(),
                decision.policy().policySetVersion(), null);
    }

    /** The {@code stamp.*} attributes for the non-null stamps. */
    public Map<String, String> toAttributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "model", model);
        put(attributes, "prompt", prompt);
        put(attributes, "tool", tool);
        put(attributes, "policy", policy);
        put(attributes, "policy-set", policySet);
        put(attributes, "definition", definition);
        return attributes;
    }

    private static void put(Map<String, String> attributes, String name, String value) {
        if (value != null) {
            attributes.put(AuditKeys.STAMP_PREFIX + name, value);
        }
    }

    private static String optional(String name, String value) {
        return value == null ? null : Checks.requireText(name, value, MAX_LENGTH);
    }
}
