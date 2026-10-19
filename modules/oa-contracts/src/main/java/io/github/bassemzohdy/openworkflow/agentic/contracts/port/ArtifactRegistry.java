package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactRef;
import java.util.Optional;

/**
 * Read access to the approved, versioned artifacts that change system behaviour (M09, M10, M26). The runtime reads
 * an approved snapshot; it does not publish. Publication belongs to the control application (ADR 0017).
 *
 * <p><b>Failure semantics: fail closed.</b>
 * <ul>
 *   <li>An artifact that is not approved, or is unknown, is an empty optional. Work that needs it is refused, not run
 *       with a default.</li>
 *   <li>An unavailable registry throws. The runtime then keeps running the versions it has already pinned and starts
 *       no new work that needs an unverified version.</li>
 *   <li>A result is never a stale or guessed version. The snapshot's freshness policy decides when a cached answer is
 *       too old to use, and the implementation returns empty rather than a version past that limit.</li>
 * </ul>
 */
public interface ArtifactRegistry {

    /** The approved version the runtime should start new work with. */
    Optional<ArtifactRef> activeVersion(ArtifactKind kind, String name);

    /** A specific version, used to resume a pinned run (ADR 0020, decision 4). */
    Optional<ArtifactRef> version(ArtifactKind kind, String name, String version);
}
