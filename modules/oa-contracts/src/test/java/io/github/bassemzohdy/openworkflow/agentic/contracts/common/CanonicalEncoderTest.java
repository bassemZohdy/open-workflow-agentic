package io.github.bassemzohdy.openworkflow.agentic.contracts.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CanonicalEncoderTest {

    @Test
    void textIsLengthPrefixedUtf8() {
        byte[] bytes = new CanonicalEncoder("d").text("ab").toBytes();
        // domain "d": 4-byte length 1, then 'd'; then "ab": 4-byte length 2, then 'a', 'b'.
        assertArrayEquals(new byte[] {0, 0, 0, 1, 'd', 0, 0, 0, 2, 'a', 'b'}, bytes);
    }

    @Test
    void nullAndEmptyOptionalTextDiffer() {
        assertNotEquals(new CanonicalEncoder("d").optionalText(null).toBytes(),
                new CanonicalEncoder("d").optionalText("").toBytes());
    }

    @Test
    void delimiterCharactersInsideTextCannotShiftFields() {
        byte[] a = new CanonicalEncoder("d").text("a\u0000b").text("c").toBytes();
        byte[] b = new CanonicalEncoder("d").text("a").text("b\u0000c").toBytes();
        assertNotEquals(new String(a, StandardCharsets.ISO_8859_1), new String(b, StandardCharsets.ISO_8859_1));
    }

    @Test
    void numbersAreEightBytes() {
        assertEquals(8, new CanonicalEncoder("").number(0).toBytes().length - new CanonicalEncoder("").toBytes().length);
    }

    @Test
    void sha256MatchesAKnownAnswer() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CanonicalEncoder.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void strictUtf8RejectsUnpairedSurrogatesAndAcceptsPairs() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalEncoder.strictUtf8("\uDC00"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalEncoder.strictUtf8("\uD800x"));
        assertArrayEquals("😀".getBytes(StandardCharsets.UTF_8),
                CanonicalEncoder.strictUtf8("😀"));
    }
}
