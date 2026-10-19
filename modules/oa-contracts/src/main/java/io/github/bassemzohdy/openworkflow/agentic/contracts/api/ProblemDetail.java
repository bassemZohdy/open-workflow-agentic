package io.github.bassemzohdy.openworkflow.agentic.contracts.api;

import java.net.URI;
import java.util.Objects;

/**
 * RFC 9457 problem details. This record holds the standard members only; the HTTP mapping
 * ({@code application/problem+json}, correlation id) is implemented in oa-runtime.
 *
 * @param type     identifies the problem type; {@code about:blank} when there is no specific type
 * @param title    short, human-readable summary of the problem type
 * @param status   the HTTP status code, 400 to 599
 * @param detail   explanation for this occurrence, or null
 * @param instance identifies this occurrence (for example the request path), or null
 */
public record ProblemDetail(URI type, String title, int status, String detail, URI instance) {

    public static final URI ABOUT_BLANK = URI.create("about:blank");

    public ProblemDetail {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("problem status must be 400 to 599, was " + status);
        }
    }

    /** A problem without a specific type ({@code about:blank}) and without an instance. */
    public static ProblemDetail of(int status, String title, String detail) {
        return new ProblemDetail(ABOUT_BLANK, title, status, detail, null);
    }
}
