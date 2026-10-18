package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A pinned reference to one version of an artifact: the kind, its name, its version and the digest of its content.
 * Running work records these references so a new release does not change in-flight runs (ADR 0020, decision 4).
 *
 * @param kind    the artifact kind
 * @param name    the artifact name, unique within its kind
 * @param version the version label, for example {@code 1.4.0}
 * @param sha256  lower-case hex SHA-256 of the artifact content
 */
public record ArtifactRef(ArtifactKind kind, String name, String version, String sha256) {

    static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public ArtifactRef {
        Objects.requireNonNull(kind, "kind");
        name = Checks.requireText("name", name, 128);
        version = Checks.requireText("version", version, 64);
        Checks.requireMatching("sha256", sha256, SHA256_HEX);
    }
}
