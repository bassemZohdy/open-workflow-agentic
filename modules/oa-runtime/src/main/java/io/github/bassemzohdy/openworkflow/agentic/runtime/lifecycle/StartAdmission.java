package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.agroal.api.AgroalDataSource;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Process-local budget for starts and reconciler repairs that can hold a registry coordination connection. */
@Singleton
public class StartAdmission {
    private final Semaphore slots;
    private final int capacity;

    @Inject
    public StartAdmission(@ConfigProperty(name = "agentic.lifecycle.max-concurrent-starts", defaultValue = "4") int limit,
                          DataSource source) {
        if (limit < 1) throw new IllegalArgumentException("agentic.lifecycle.max-concurrent-starts must be at least 1");
        this.capacity = poolBound(limit, source);
        slots = new Semaphore(capacity);
    }

    /** Explicit callers without a known pooled datasource retain the requested bound. */
    public StartAdmission(int limit) { this(limit, null); }

    private static int poolBound(int limit, DataSource source) {
        if (source == null) return limit;
        try {
            AgroalDataSource pool = source instanceof AgroalDataSource agroal ? agroal
                    : source.isWrapperFor(AgroalDataSource.class) ? source.unwrap(AgroalDataSource.class) : null;
            if (pool == null) return limit;
            int maximum = pool.getConfiguration().connectionPoolConfiguration().maxSize();
            if (maximum < 2) throw new IllegalArgumentException("the registry pool needs at least two connections for coordinated starts");
            return Math.min(limit, maximum - 1);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot inspect registry pool capacity", e);
        }
    }

    int capacity() { return capacity; }

    boolean tryAcquire() { return slots.tryAcquire(); }
    void release() { slots.release(); }
}
