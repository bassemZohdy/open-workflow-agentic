package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import java.util.List;
import java.util.Objects;

/**
 * A snapshot as an approved-state source publishes it, before the runtime has checked it. The declared hash is what
 * the publisher claims, and {@link PolicySnapshot} accepts it only when the texts really hash to it.
 *
 * @param versionId          the claimed version id
 * @param declaredContentHash the claimed content hash
 * @param policyTexts        the Cedar texts as read
 */
public record PublishedPolicySnapshot(String versionId, String declaredContentHash, List<String> policyTexts) {

    public PublishedPolicySnapshot {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(declaredContentHash, "declaredContentHash");
        policyTexts = List.copyOf(Objects.requireNonNull(policyTexts, "policyTexts"));
    }
}
