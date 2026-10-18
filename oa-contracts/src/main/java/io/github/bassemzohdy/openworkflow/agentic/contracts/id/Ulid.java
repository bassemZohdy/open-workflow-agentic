package io.github.bassemzohdy.openworkflow.agentic.contracts.id;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A ULID: 48 bits of millisecond timestamp followed by 80 random bits, encoded as 26 characters of
 * Crockford base32. Lexicographic order of the text equals chronological order, so ids sort by creation time.
 *
 * <p>Format: {@code [0-7][0-9A-HJKMNP-TV-Z]{25}} (upper case). {@link #parse(String)} accepts lower case too.
 * See {@link UlidGenerator} to create new values.
 */
public record Ulid(String value) implements Comparable<Ulid> {

    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int LENGTH = 26;
    static final int RANDOM_BITS = 80;

    private static final Pattern FORMAT = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

    public Ulid {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("not a ULID: " + value);
        }
    }

    /** Parses the canonical text form; lower case is accepted and normalised to upper case. */
    public static Ulid parse(String text) {
        Objects.requireNonNull(text, "text");
        return new Ulid(text.toUpperCase(Locale.ROOT));
    }

    /** The millisecond timestamp embedded in this id. */
    public Instant timestamp() {
        return Instant.ofEpochMilli(toBigInteger().shiftRight(RANDOM_BITS).longValueExact());
    }

    @Override
    public int compareTo(Ulid other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }

    /** Builds the text form from a 48-bit timestamp and an 80-bit random part. Used by the generator. */
    static Ulid of(long epochMillis, BigInteger random) {
        BigInteger v = BigInteger.valueOf(epochMillis).shiftLeft(RANDOM_BITS).or(random);
        char[] out = new char[LENGTH];
        for (int i = LENGTH - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt(v.intValue() & 31);
            v = v.shiftRight(5);
        }
        return new Ulid(new String(out));
    }

    private BigInteger toBigInteger() {
        BigInteger v = BigInteger.ZERO;
        for (int i = 0; i < value.length(); i++) {
            v = v.shiftLeft(5).or(BigInteger.valueOf(ALPHABET.indexOf(value.charAt(i))));
        }
        return v;
    }
}
