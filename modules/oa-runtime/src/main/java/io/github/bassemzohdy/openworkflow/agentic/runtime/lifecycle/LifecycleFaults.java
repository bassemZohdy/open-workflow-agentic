package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;
import org.jboss.logging.Logger;

/**
 * Dev-only fault points for process-level tests (WP-105c, scope item 6). Set {@code agentic.dev.fault.crash-at} to one
 * of the point names and the process halts with {@code Runtime.halt(137)} when it reaches that point, which leaves the
 * database exactly as a {@code kill -9} would. Unset (the default) the points do nothing.
 *
 * <p>Never set this in a deployment: it is the same kind of switch as {@code agentic.dev.trust-caller-headers}.
 */
@Singleton
public class LifecycleFaults {

    /** After the registry row is committed, before the engine is prepared (no engine id yet). */
    public static final String AFTER_INSERT = "after-insert";
    /** After the engine id is recorded, before the engine is started. */
    public static final String AFTER_PREPARE = "after-prepare";
    /** After the engine accepted the start, before the registry records RUNNING. */
    public static final String BEFORE_ACCEPT = "before-accept";

    private static final Logger LOG = Logger.getLogger(LifecycleFaults.class);

    private final String crashAt;

    @Inject
    public LifecycleFaults(LifecycleSettings settings) {
        this((String) settings.crashAt.orElse(null));
    }

    LifecycleFaults(String crashAt) {
        this.crashAt = crashAt;
    }

    /** Faults that never fire. */
    public static LifecycleFaults none() {
        return new LifecycleFaults((String) null);
    }

    /** Halts the process if this point is the configured one. */
    public void hit(String point) {
        Objects.requireNonNull(point, "point");
        if (point.equals(crashAt)) {
            LOG.errorf("dev fault point %s reached: halting the process as kill -9 would", point);
            Runtime.getRuntime().halt(137);
        }
    }
}
