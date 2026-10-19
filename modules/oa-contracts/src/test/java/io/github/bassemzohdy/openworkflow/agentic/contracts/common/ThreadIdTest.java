package io.github.bassemzohdy.openworkflow.agentic.contracts.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ThreadIdTest {

    @Test
    void derivedNameHasThePrefixAndSixtyFourHexDigits() {
        ThreadId id = ThreadId.derive("acme", "u-1", "instance-7");
        assertEquals(ThreadId.LENGTH, id.value().length());
        assertEquals(68, id.value().length());
        assertTrue(id.value().startsWith("ws1-"));
        assertTrue(id.value().substring(4).matches("[0-9a-f]{64}"));
    }

    @Test
    void sameScopeGivesTheSameName() {
        assertEquals(ThreadId.derive("t", "u", "i"), ThreadId.derive("t", "u", "i"));
    }

    @Test
    void eachPartChangesTheName() {
        ThreadId base = ThreadId.derive("t", "u", "i");
        assertNotEquals(base, ThreadId.derive("T", "u", "i"));
        assertNotEquals(base, ThreadId.derive("t", "U", "i"));
        assertNotEquals(base, ThreadId.derive("t", "u", "I"));
    }

    @Test
    void delimiterInsideAPartDoesNotCollideWithAnotherSplit() {
        // The collision that the WP-004 spike found for a '|'-joined id (Q05): both joins read "acme|x|y|9".
        assertNotEquals(ThreadId.derive("acme", "x|y", "9"), ThreadId.derive("acme|x", "y", "9"));
    }

    @Test
    void hyphenAndColonInsidePartsDoNotCollide() {
        assertNotEquals(ThreadId.derive("a-b", "c", "d"), ThreadId.derive("a", "b-c", "d"));
        assertNotEquals(ThreadId.derive("a", "b", "c-d"), ThreadId.derive("a-b", "c", "d"));
        assertNotEquals(ThreadId.derive("a:b", "c", "d"), ThreadId.derive("a", "b:c", "d"));
        assertNotEquals(ThreadId.derive("x", "1:y", "z"), ThreadId.derive("x", "1", "y:z"));
    }

    @Test
    void lengthPrefixStopsShiftedBoundaries() {
        // Concatenating the parts gives "abc" in both cases; the length prefix tells them apart.
        assertNotEquals(ThreadId.derive("ab", "c", "d"), ThreadId.derive("a", "bc", "d"));
        assertNotEquals(ThreadId.derive("a", "bc", "d"), ThreadId.derive("a", "b", "cd"));
    }

    @Test
    void everyTupleOverASmallDelimiterAlphabetGetsADistinctName() {
        // Exhaustive check: all non-empty parts of length 1 or 2 over characters that act as delimiters or digits.
        List<String> parts = new ArrayList<>();
        for (String a : new String[] {"a", "-", ":", "|", "1"}) {
            parts.add(a);
            for (String b : new String[] {"a", "-", ":", "|", "1"}) {
                parts.add(a + b);
            }
        }
        Set<String> names = new HashSet<>();
        int tuples = 0;
        for (String tenant : parts) {
            for (String user : parts) {
                for (String instance : parts) {
                    names.add(ThreadId.derive(tenant, user, instance).value());
                    tuples++;
                }
            }
        }
        assertEquals(tuples, names.size(), "no two of " + tuples + " tuples may share a thread name");
    }

    @Test
    void unicodeIsNotNormalisedSoCanonicallyEqualTextGetsDifferentNames() {
        // The exact stored text is the scope; "é" as one code point and as e plus combining accent are different.
        assertNotEquals(ThreadId.derive("t", "é", "i"), ThreadId.derive("t", "é", "i"));
    }

    @Test
    void blankOrNullPartsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ThreadId.derive(" ", "u", "i"));
        assertThrows(IllegalArgumentException.class, () -> ThreadId.derive("t", "", "i"));
        assertThrows(NullPointerException.class, () -> ThreadId.derive("t", "u", null));
    }

    @Test
    void unpairedSurrogateIsRejectedInsteadOfEncodedAsReplacement() {
        String lone = "x\uD800";
        assertThrows(IllegalArgumentException.class, () -> ThreadId.derive(lone, "u", "i"));
    }

    @Test
    void oversizedPartIsRejected() {
        String tooLong = "t".repeat(ThreadId.MAX_PART_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> ThreadId.derive(tooLong, "u", "i"));
        assertEquals(68, ThreadId.derive("t".repeat(ThreadId.MAX_PART_LENGTH), "u", "i").value().length());
    }

    @Test
    void parseAcceptsOnlyTheDerivedShape() {
        String good = ThreadId.derive("t", "u", "i").value();
        assertEquals(good, ThreadId.parse(good).value());
        assertThrows(IllegalArgumentException.class, () -> ThreadId.parse("ws1-" + "a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> ThreadId.parse("ws1-" + "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> ThreadId.parse("ws2-" + "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> ThreadId.parse("acme|x|y|9"));
        assertThrows(NullPointerException.class, () -> ThreadId.parse(null));
    }

    @Test
    void theNameDoesNotContainTheRawInput() {
        ThreadId id = ThreadId.derive("tenant-secret", "user-secret", "instance-secret");
        assertFalse(id.value().contains("secret"));
    }
}
