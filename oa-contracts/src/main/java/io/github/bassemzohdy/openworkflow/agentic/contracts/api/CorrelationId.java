package io.github.bassemzohdy.openworkflow.agentic.contracts.api;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Identifier that follows one request across services and into the logs. Clients may send it in the
 * {@value #HEADER} header; the server echoes it back. A missing or invalid value is replaced by a new one, so an
 * unsafe value from a client is never copied into logs or responses.
 */
public record CorrelationId(String value) {

    public static final String HEADER = "X-Correlation-Id";
    public static final int MAX_LENGTH = 128;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}");

    public CorrelationId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid correlation id");
        }
    }

    /** The value if the raw header text is a valid correlation id (surrounding spaces ignored), otherwise empty. */
    public static Optional<CorrelationId> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        return VALID.matcher(trimmed).matches() ? Optional.of(new CorrelationId(trimmed)) : Optional.empty();
    }

    /** Uses the client's value when valid, otherwise generates a new one from {@code generator}. */
    public static CorrelationId resolve(String rawHeader, UlidGenerator generator) {
        return parse(rawHeader).orElseGet(() -> new CorrelationId(generator.next().value()));
    }

    /** As {@link #resolve(String, UlidGenerator)} with the shared ULID generator. */
    public static CorrelationId resolve(String rawHeader) {
        return resolve(rawHeader, UlidGenerator.shared());
    }
}
