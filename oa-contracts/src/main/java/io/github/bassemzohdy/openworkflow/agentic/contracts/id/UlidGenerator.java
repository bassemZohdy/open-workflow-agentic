package io.github.bassemzohdy.openworkflow.agentic.contracts.id;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;

/**
 * Creates ULIDs that are strictly increasing within one generator, including several ids in the same
 * millisecond: the random part is incremented instead of drawn again. If the random part overflows within a
 * millisecond the timestamp is advanced by one millisecond. A clock that moves backwards does not break the order.
 *
 * <p>Thread-safe. Use {@link #shared()} for application ids.
 */
public final class UlidGenerator {

    private static final long MAX_TIMESTAMP = (1L << 48) - 1;
    private static final BigInteger MAX_RANDOM = BigInteger.ONE.shiftLeft(Ulid.RANDOM_BITS).subtract(BigInteger.ONE);

    private static final UlidGenerator SHARED = new UlidGenerator(Clock.systemUTC(), new SecureRandom());

    private final Clock clock;
    private final SecureRandom random;
    private long lastTime = -1;
    private BigInteger lastRandom = BigInteger.ZERO;

    public UlidGenerator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** The process-wide generator backed by the system clock and a secure random source. */
    public static UlidGenerator shared() {
        return SHARED;
    }

    public synchronized Ulid next() {
        long now = clock.millis();
        if (now > lastTime) {
            lastTime = now;
            lastRandom = randomPart();
        } else if (lastRandom.equals(MAX_RANDOM)) {
            lastTime = lastTime + 1;
            lastRandom = randomPart();
        } else {
            lastRandom = lastRandom.add(BigInteger.ONE);
        }
        if (lastTime > MAX_TIMESTAMP) {
            throw new IllegalStateException("ULID timestamp exhausted");
        }
        return Ulid.of(lastTime, lastRandom);
    }

    private BigInteger randomPart() {
        return new BigInteger(Ulid.RANDOM_BITS, random);
    }
}
