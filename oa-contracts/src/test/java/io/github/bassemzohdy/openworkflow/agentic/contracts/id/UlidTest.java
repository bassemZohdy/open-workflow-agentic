package io.github.bassemzohdy.openworkflow.agentic.contracts.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UlidTest {

    private static final String FORMAT = "[0-7][0-9A-HJKMNP-TV-Z]{25}";

    private static Clock fixed(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    @Test
    void decodesTheTimestampOfAKnownUlid() {
        // Decoded independently (Crockford base32, first 10 characters = 48 bits of milliseconds).
        assertEquals(1469922850259L, Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV").timestamp().toEpochMilli());
    }

    @Test
    void parseAcceptsLowerCaseAndNormalises() {
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", Ulid.parse("01arz3ndektsv4rrffq69g5fav").value());
    }

    @Test
    void rejectsMalformedText() {
        assertThrows(IllegalArgumentException.class, () -> Ulid.parse("8ZZZZZZZZZZZZZZZZZZZZZZZZZ"), "first char above 7");
        assertThrows(IllegalArgumentException.class, () -> Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FA"), "25 characters");
        assertThrows(IllegalArgumentException.class, () -> Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FI"), "letter I is not Crockford");
        assertThrows(IllegalArgumentException.class, () -> Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FU"), "letter U is not Crockford");
        assertThrows(IllegalArgumentException.class, () -> Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5F!"), "symbol");
    }

    @Test
    void generatedIdsHaveTheCanonicalFormatAndTheClockTimestamp() {
        UlidGenerator generator = new UlidGenerator(fixed(1_700_000_000_123L), new SecureRandom());
        Ulid id = generator.next();
        assertTrue(id.value().matches(FORMAT), id.value());
        assertEquals(1_700_000_000_123L, id.timestamp().toEpochMilli());
    }

    @Test
    void idsInTheSameMillisecondAreStrictlyIncreasing() {
        UlidGenerator generator = new UlidGenerator(fixed(1_700_000_000_000L), new SecureRandom());
        List<Ulid> ids = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            ids.add(generator.next());
        }
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i - 1).compareTo(ids.get(i)) < 0, "order broken at " + i);
        }
    }

    @Test
    void orderFollowsTheClockAndSurvivesItMovingBackwards() {
        long[] times = {2000, 1990, 1995, 2001};
        Clock jumpy = new Clock() {
            int next = 0;

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis());
            }

            @Override
            public long millis() {
                return times[Math.min(next++, times.length - 1)];
            }
        };
        UlidGenerator generator = new UlidGenerator(jumpy, new SecureRandom());
        Ulid a = generator.next();
        Ulid b = generator.next();
        Ulid c = generator.next();
        Ulid d = generator.next();
        assertTrue(a.compareTo(b) < 0 && b.compareTo(c) < 0 && c.compareTo(d) < 0);
    }

    @Test
    void randomOverflowWithinOneMillisecondMovesTheTimestampForward() {
        SecureRandom allOnes = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) 0xFF);
            }
        };
        UlidGenerator generator = new UlidGenerator(fixed(5_000L), allOnes);
        Ulid first = generator.next();
        Ulid second = generator.next();
        assertTrue(first.value().endsWith("ZZZZZZZZZZZZZZZZ"));
        assertTrue(second.compareTo(first) > 0);
        assertEquals(5_001L, second.timestamp().toEpochMilli());
    }

    @Test
    void timestampExhaustionFailsInsteadOfWrapping() {
        SecureRandom allOnes = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                Arrays.fill(bytes, (byte) 0xFF);
            }
        };
        UlidGenerator generator = new UlidGenerator(fixed((1L << 48) - 1), allOnes);
        generator.next();
        assertThrows(IllegalStateException.class, generator::next);
    }

    @Test
    void sharedGeneratorIsASingleton() {
        assertFalse(UlidGenerator.shared() == null);
        assertTrue(UlidGenerator.shared() == UlidGenerator.shared());
    }
}
