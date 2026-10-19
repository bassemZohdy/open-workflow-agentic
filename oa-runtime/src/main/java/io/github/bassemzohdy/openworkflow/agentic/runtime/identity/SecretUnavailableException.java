package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

/**
 * A secret could not be read, or is not usable. Work that needs the secret must pause or fail; it must never continue
 * with a guessed, empty or stale-forever value. The message names the secret, never its value.
 */
public final class SecretUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecretUnavailableException(String message) {
        super(message);
    }

    public SecretUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
