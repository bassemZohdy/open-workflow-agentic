package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A replay cache in memory, for one process. Bounded: when full, expired entries are dropped, and if it is still full
 * the use is refused (fail closed) rather than forgetting a live token id. Use {@link JdbcReplayCache} where more than
 * one process accepts the tokens or where a restart must not forget them.
 */
public final class InMemoryReplayCache implements ReplayCache {

    private static final Duration KEEP_AFTER_EXPIRY = Duration.ofMinutes(1);

    private record Entry(int uses, Instant expiresAt) {
    }

    private final int capacity;
    private final Map<String, Entry> entries = new HashMap<>();

    public InMemoryReplayCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized boolean use(String key, Instant expiresAt, int maxUses, Instant now) {
        if (maxUses < 1) {
            throw new IllegalArgumentException("maxUses must be at least 1");
        }
        Entry existing = entries.get(key);
        if (existing != null) {
            if (existing.uses() >= maxUses) {
                return false;
            }
            entries.put(key, new Entry(existing.uses() + 1, existing.expiresAt()));
            return true;
        }
        if (entries.size() >= capacity) {
            purge(now);
            if (entries.size() >= capacity) {
                return false;
            }
        }
        entries.put(key, new Entry(1, expiresAt));
        return true;
    }

    private void purge(Instant now) {
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            if (it.next().expiresAt().plus(KEEP_AFTER_EXPIRY).isBefore(now)) {
                it.remove();
            }
        }
    }
}
