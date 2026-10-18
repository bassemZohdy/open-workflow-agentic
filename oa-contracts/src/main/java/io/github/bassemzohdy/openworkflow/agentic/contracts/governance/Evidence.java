package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A reference to something an agent step relied on, with a digest of its content (M15). The content itself is not
 * stored here: sensitive payloads are referenced by {@code reference} and kept under their retention rules.
 *
 * @param id          the evidence id
 * @param agentTaskId the agent task that relied on it
 * @param kind        what was captured
 * @param reference   where the content lives: a URI or a store key, at most {@link #MAX_REFERENCE_LENGTH} characters
 * @param sha256      lower-case hex SHA-256 of the content at capture time
 * @param capturedAt  when it was captured
 */
public record Evidence(Ulid id, Ulid agentTaskId, EvidenceKind kind, String reference, String sha256,
                       Instant capturedAt) {

    public static final int MAX_REFERENCE_LENGTH = 2_048;
    static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public Evidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentTaskId, "agentTaskId");
        Objects.requireNonNull(kind, "kind");
        reference = Checks.requireText("reference", reference, MAX_REFERENCE_LENGTH);
        Checks.requireMatching("sha256", sha256, SHA256_HEX);
        Objects.requireNonNull(capturedAt, "capturedAt");
    }
}
