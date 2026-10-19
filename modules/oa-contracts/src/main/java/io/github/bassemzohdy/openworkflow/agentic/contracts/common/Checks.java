package io.github.bassemzohdy.openworkflow.agentic.contracts.common;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Argument checks shared by the contract records. A null argument is a {@link NullPointerException}; every other
 * violation is an {@link IllegalArgumentException} that names the field. Collections are copied, so callers cannot
 * change a contract after it is built.
 */
public final class Checks {

    private Checks() {
    }

    /** Non-blank text of at most {@code maxLength} characters. */
    public static String requireText(String name, String value, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " longer than " + maxLength + " characters");
        }
        return value;
    }

    /** Text that must match {@code pattern} as a whole. The message does not echo the value. */
    public static String requireMatching(String name, String value, Pattern pattern) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid format");
        }
        return value;
    }

    /** An integer in the inclusive range {@code [min, max]}. */
    public static int requireInRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ", was " + value);
        }
        return value;
    }

    /** A long in the inclusive range {@code [min, max]}. */
    public static long requireInRange(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ", was " + value);
        }
        return value;
    }

    /** An immutable copy of a set with at most {@code maxSize} entries. */
    public static <T> Set<T> copySet(String name, Set<T> values, int maxSize) {
        Objects.requireNonNull(values, name);
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(name + " has more than " + maxSize + " entries");
        }
        return Set.copyOf(values);
    }

    /** An immutable set of tokens, each matching {@code pattern}, with at most {@code maxSize} entries. */
    public static Set<String> copyTokens(String name, Set<String> values, int maxSize, Pattern pattern) {
        Set<String> copy = copySet(name, values, maxSize);
        for (String value : copy) {
            requireMatching(name + " entry", value, pattern);
        }
        return copy;
    }

    /** An immutable copy of a list with at most {@code maxSize} entries. */
    public static <T> List<T> copyList(String name, List<T> values, int maxSize) {
        Objects.requireNonNull(values, name);
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(name + " has more than " + maxSize + " entries");
        }
        return List.copyOf(values);
    }

    /** An immutable map with at most {@code maxEntries} entries and values of at most {@code maxValueLength}. */
    public static Map<String, String> copyMap(String name, Map<String, String> values, int maxEntries,
                                              int maxValueLength) {
        Objects.requireNonNull(values, name);
        if (values.size() > maxEntries) {
            throw new IllegalArgumentException(name + " has more than " + maxEntries + " entries");
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            requireText(name + " key", entry.getKey(), maxValueLength);
            requireText(name + " value", entry.getValue(), maxValueLength);
        }
        return Map.copyOf(values);
    }
}
