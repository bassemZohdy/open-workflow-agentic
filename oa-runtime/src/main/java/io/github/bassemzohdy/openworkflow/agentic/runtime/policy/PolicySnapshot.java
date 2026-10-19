package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One approved, versioned set of policy texts that the runtime evaluates (M12, M26, M27). A snapshot can only exist
 * when its content hash matches its texts, so a tampered, truncated or partly written set cannot be turned into a
 * snapshot at all.
 *
 * <p>The hash covers the version id and every text, in order, with the canonical length-prefixed encoding. The
 * version id is therefore part of what was approved, and two different sets never share a hash.
 *
 * @param versionId   the approved version, for example {@code 2026-10-18.1}; attached to every decision
 * @param contentHash lower-case hex SHA-256 over the version id and the texts
 * @param policyTexts the Cedar texts in declaration order; may be empty, which denies every request
 */
public record PolicySnapshot(String versionId, String contentHash, List<String> policyTexts) {

    /** Domain tag of the content hash. Changing it changes every hash. */
    public static final String HASH_DOMAIN = "oa.policy-snapshot.v1";
    public static final int MAX_VERSION_LENGTH = 64;
    public static final int MAX_POLICIES = 256;
    public static final int MAX_POLICY_TEXT_LENGTH = 256 * 1024;

    static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public PolicySnapshot {
        Checks.requireMatching("versionId", versionId, VERSION);
        Checks.requireMatching("contentHash", contentHash, SHA256_HEX);
        policyTexts = Checks.copyList("policyTexts", policyTexts, MAX_POLICIES);
        for (String text : policyTexts) {
            Checks.requireInRange("policy text length", text.length(), 0, MAX_POLICY_TEXT_LENGTH);
        }
        String actual = hashOf(versionId, policyTexts);
        if (!actual.equals(contentHash)) {
            throw new IllegalArgumentException("content hash does not match the policy texts");
        }
    }

    /** Builds a snapshot and computes its hash. Used by the publisher side and by tests. */
    public static PolicySnapshot create(String versionId, List<String> policyTexts) {
        return new PolicySnapshot(versionId, hashOf(versionId, policyTexts), policyTexts);
    }

    /** The content hash of a version id and its texts, lower-case hex. */
    public static String hashOf(String versionId, List<String> policyTexts) {
        CanonicalEncoder encoder = new CanonicalEncoder(HASH_DOMAIN)
                .text(versionId)
                .number(policyTexts.size());
        for (String text : policyTexts) {
            encoder.text(text);
        }
        return CanonicalEncoder.sha256Hex(encoder.toBytes());
    }
}
