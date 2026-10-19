package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

/**
 * Where the runtime gets a secret (a client secret, a signing key password). A secret is read by name when it is
 * needed, so a rotated value is picked up without a restart. Rotating values are never in environment variables,
 * configuration files read once at start, workflow state or logs. See {@link FileSecretProvider}.
 */
public interface SecretProvider {

    /**
     * The current value of the secret.
     *
     * @throws SecretUnavailableException when the secret does not exist, cannot be read, or is empty. The caller
     *                                    pauses or fails the work that needs it; it never continues without.
     */
    Credential current(String name);
}
