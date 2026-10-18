package io.github.bassemzohdy.openworkflow.agentic.contracts.doubles;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ArtifactRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reference registry for tests. Publishing is a test-setup method; the runtime port is read-only. */
public final class InMemoryArtifactRegistry implements ArtifactRegistry {

    private final Map<String, ArtifactRef> active = new HashMap<>();
    private final Map<String, ArtifactRef> versions = new HashMap<>();

    /** Adds a version; when {@code makeActive} is true it also becomes the approved version. */
    public synchronized void publish(ArtifactRef ref, boolean makeActive) {
        versions.put(key(ref.kind(), ref.name(), ref.version()), ref);
        if (makeActive) {
            active.put(key(ref.kind(), ref.name()), ref);
        }
    }

    @Override
    public synchronized Optional<ArtifactRef> activeVersion(ArtifactKind kind, String name) {
        return Optional.ofNullable(active.get(key(kind, name)));
    }

    @Override
    public synchronized Optional<ArtifactRef> version(ArtifactKind kind, String name, String version) {
        return Optional.ofNullable(versions.get(key(kind, name, version)));
    }

    private static String key(ArtifactKind kind, String name) {
        return kind + "/" + name;
    }

    private static String key(ArtifactKind kind, String name, String version) {
        return kind + "/" + name + "@" + version;
    }

    /** Every version stored, for assertions. */
    public synchronized List<ArtifactRef> all() {
        return List.copyOf(versions.values());
    }
}
