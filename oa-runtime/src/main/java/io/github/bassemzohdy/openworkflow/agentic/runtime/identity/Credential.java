package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * A secret text value: a bearer token, a client secret, a password. The only way to read it is {@link #reveal()}, which
 * names what the caller is doing. Every other route is closed so that a credential cannot reach a log line, an audit
 * attribute, a JSON body or a database row by accident:
 *
 * <ul>
 *   <li>{@link #toString()} returns a fixed marker, never a prefix, length or digest.</li>
 *   <li>Jackson serialization writes the same marker ({@link JsonValue}), so a record that holds a credential cannot
 *       leak it into JSON.</li>
 *   <li>The class is not {@link java.io.Serializable}.</li>
 *   <li>{@link #equals} and {@link #hashCode} are identity-based, so a credential does not reveal its content through
 *       lookups.</li>
 * </ul>
 *
 * <p>Java strings cannot be wiped from memory. This type limits where a value travels; it does not erase it.
 */
public final class Credential {

    /** What {@link #toString()} and JSON serialization show instead of the value. */
    public static final String REDACTED = "[REDACTED]";

    private final String value;

    private Credential(String value) {
        this.value = value;
    }

    /** Wraps a non-blank value. */
    public static Credential of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a credential must not be blank");
        }
        return new Credential(value);
    }

    /** The value. Call this only at the point where the credential is sent or checked, and do not store the result. */
    public String reveal() {
        return value;
    }

    @Override
    @JsonValue
    public String toString() {
        return REDACTED;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
