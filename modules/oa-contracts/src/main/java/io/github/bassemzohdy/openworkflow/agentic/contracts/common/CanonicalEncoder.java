package io.github.bassemzohdy.openworkflow.agentic.contracts.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Injective byte encoding for values that are hashed (thread ids, audit events).
 *
 * <p>Every field is written with a fixed width or an explicit length: text as a 4-byte length followed by its UTF-8
 * bytes, numbers as 8 bytes, optional text with a presence byte. No two different field sequences give the same
 * bytes, whatever characters they contain, so a delimiter inside a value cannot make two tuples collide. The first
 * field is a domain tag, so the same fields hashed for two purposes never collide.
 *
 * <p>Text is encoded strictly: an unpaired surrogate is rejected, because a lenient encoder would map it to the same
 * bytes as another character.
 */
public final class CanonicalEncoder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** Starts an encoding with a domain tag, for example {@code "oa.thread.v1"}. */
    public CanonicalEncoder(String domain) {
        text(domain);
    }

    /** Appends non-null text. */
    public CanonicalEncoder text(String value) {
        byte[] bytes = strictUtf8(Objects.requireNonNull(value, "value"));
        writeInt(bytes.length);
        out.writeBytes(bytes);
        return this;
    }

    /** Appends text that may be null; null and the empty string encode differently. */
    public CanonicalEncoder optionalText(String value) {
        if (value == null) {
            out.write(0);
            return this;
        }
        out.write(1);
        return text(value);
    }

    /** Appends a 64-bit integer in big-endian order. */
    public CanonicalEncoder number(long value) {
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
        return this;
    }

    /** The encoded bytes so far. */
    public byte[] toBytes() {
        return out.toByteArray();
    }

    /** Lower-case hexadecimal SHA-256 of {@code input}. */
    public static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java 21 runtime", e);
        }
    }

    /** UTF-8 bytes of {@code value}; rejects unpaired surrogates instead of replacing them. */
    public static byte[] strictUtf8(String value) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("text is not well-formed UTF-16 (unpaired surrogate)", e);
        }
    }

    private void writeInt(int value) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
