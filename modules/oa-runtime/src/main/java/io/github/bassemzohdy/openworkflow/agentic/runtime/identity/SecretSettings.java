package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/**
 * Where secrets live (docs/identity.md section 6).
 *
 * <pre>
 * agentic.secrets.dir=/var/run/secrets/agentic
 * agentic.secrets.ttl=PT30S
 * agentic.secrets.max-stale=PT2M
 * </pre>
 */
@ConfigMapping(prefix = "agentic.secrets")
public interface SecretSettings {

    /** The directory of secret files, for example a mounted Kubernetes Secret. Without it, every secret is unavailable. */
    Optional<String> dir();

    /** How long an unchanged file is trusted before it is read again. A changed file is noticed at once. */
    @WithDefault("PT30S")
    Duration ttl();

    /** How long a cached value may still be used after {@code ttl} while the file cannot be read; then it fails. */
    @WithDefault("PT2M")
    Duration maxStale();
}
