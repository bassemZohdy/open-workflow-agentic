package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.Semaphore;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Process-local bound on starts that can hold a registry coordination connection. */
@Singleton
public class StartAdmission {
    private final Semaphore slots;

    @Inject
    public StartAdmission(@ConfigProperty(name = "agentic.lifecycle.max-concurrent-starts", defaultValue = "4") int limit) {
        if (limit < 1) throw new IllegalArgumentException("agentic.lifecycle.max-concurrent-starts must be at least 1");
        slots = new Semaphore(limit);
    }

    boolean tryAcquire() { return slots.tryAcquire(); }
    void release() { slots.release(); }
}
