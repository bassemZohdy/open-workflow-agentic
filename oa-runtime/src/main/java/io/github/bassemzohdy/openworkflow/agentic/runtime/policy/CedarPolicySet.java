package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles the Cedar texts of one snapshot into an engine policy set, and maps the engine's own policy names to the
 * stable ids the platform records.
 *
 * <p>Cedar names policies by position in the parsed text (`policy0`, `policy1`, ...), so reordering a file changes
 * the names. Each policy must therefore carry an {@code @id} annotation, which becomes its stable id. A policy without
 * one, two policies with the same one, an id outside {@link #ID_PATTERN}, or a template is refused, and so is a text
 * that parses differently when joined with the others than it does alone. Each refusal is an
 * {@link IllegalArgumentException} that names the problem and not the policy text.
 */
final class CedarPolicySet {

    /** Format of a stable policy id, as recorded in decisions. */
    static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9._-]{0,127}");

    /**
     * A compiled snapshot.
     *
     * @param policies    the engine policy set
     * @param stableIds   engine policy name (for example {@code policy0}) to stable id
     */
    record Compiled(PolicySet policies, Map<String, String> stableIds) {
    }

    private CedarPolicySet() {
    }

    static Compiled compile(List<String> texts) {
        int expected = 0;
        for (String text : texts) {
            PolicySet alone = parse(text);
            requireNoTemplates(alone);
            expected += alone.getNumPolicies();
        }
        PolicySet joined = parse(String.join("\n", texts));
        requireNoTemplates(joined);
        if (joined.getNumPolicies() != expected) {
            throw new IllegalArgumentException("policy texts do not combine into the same policies");
        }
        Map<String, String> stableIds = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Policy policy : joined.policies) {
            String id = stableId(policy);
            if (!seen.add(id)) {
                throw new IllegalArgumentException("duplicate policy id " + id);
            }
            stableIds.put(policy.policyID, id);
        }
        return new Compiled(joined, Map.copyOf(stableIds));
    }

    private static PolicySet parse(String text) {
        try {
            return PolicySet.parsePolicies(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("policy text does not parse (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static void requireNoTemplates(PolicySet set) {
        if (set.getNumTemplates() > 0) {
            throw new IllegalArgumentException("policy templates are not allowed in a snapshot");
        }
    }

    private static String stableId(Policy policy) {
        String id;
        try {
            id = policy.getAnnotation("id");
        } catch (Exception e) {
            throw new IllegalArgumentException("policy annotations cannot be read (" + e.getClass().getSimpleName() + ")",
                    e);
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("every policy needs an @id annotation");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("policy id has an invalid format");
        }
        return id;
    }
}
