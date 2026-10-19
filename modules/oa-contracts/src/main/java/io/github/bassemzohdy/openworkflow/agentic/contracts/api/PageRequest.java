package io.github.bassemzohdy.openworkflow.agentic.contracts.api;

/**
 * Cursor-based pagination request. The cursor is opaque to clients. The limit is at least 1 and is capped at
 * {@link #MAX_LIMIT}; a larger request is reduced to the cap rather than rejected.
 *
 * @param cursor opaque position returned by the previous page, or null for the first page
 * @param limit  number of items wanted, 1 to {@link #MAX_LIMIT}
 */
public record PageRequest(String cursor, int limit) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_CURSOR_LENGTH = 512;

    public PageRequest {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        limit = Math.min(limit, MAX_LIMIT);
        if (cursor != null && cursor.isBlank()) {
            cursor = null;
        }
        if (cursor != null && cursor.length() > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException("cursor longer than " + MAX_CURSOR_LENGTH + " characters");
        }
    }

    /** The first page with the default limit. */
    public static PageRequest firstPage() {
        return new PageRequest(null, DEFAULT_LIMIT);
    }

    /** Builds a request from optional query parameters; a missing limit means {@link #DEFAULT_LIMIT}. */
    public static PageRequest of(String cursor, Integer limit) {
        return new PageRequest(cursor, limit == null ? DEFAULT_LIMIT : limit);
    }
}
