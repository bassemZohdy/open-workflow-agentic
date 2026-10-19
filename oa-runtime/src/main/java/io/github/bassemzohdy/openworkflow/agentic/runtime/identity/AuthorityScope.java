package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jboss.logging.MDC;

/**
 * The identity of the instance a piece of work runs for, on the current thread: subject, delegation grant and correlation
 * id (identifiers only). It is carried into asynchronous work with the {@code wrap} methods and re-established after a
 * restart from the registry ({@link InstanceAuthorities#findByEngineInstanceId}); it is never serialized into workflow
 * state.
 *
 * <p><b>This is context, not authentication.</b> A scope restored from an engine instance id says which instance the work
 * belongs to. It is used for log and audit attribution and to find the grant a step should exchange from. It must never
 * be the basis of an authorization decision for an incoming request: those use a verified token.
 *
 * <p>The scope also sets the logging context keys {@value #MDC_SUBJECT}, {@value #MDC_GRANT} and {@value #MDC_INSTANCE}
 * for its duration.
 */
public final class AuthorityScope implements AutoCloseable {

    public static final String MDC_SUBJECT = "oa.subject";
    public static final String MDC_GRANT = "oa.grant";
    public static final String MDC_INSTANCE = "oa.instance";

    private static final ThreadLocal<InstanceAuthority> CURRENT = new ThreadLocal<>();

    private final InstanceAuthority previous;
    private final Object previousSubject;
    private final Object previousGrant;
    private final Object previousInstance;
    private boolean closed;

    private AuthorityScope(InstanceAuthority authority) {
        this.previous = CURRENT.get();
        this.previousSubject = MDC.get(MDC_SUBJECT);
        this.previousGrant = MDC.get(MDC_GRANT);
        this.previousInstance = MDC.get(MDC_INSTANCE);
        CURRENT.set(authority);
        MDC.put(MDC_SUBJECT, authority.subject().id());
        MDC.put(MDC_INSTANCE, authority.instanceId().value());
        if (authority.grantId() != null) {
            MDC.put(MDC_GRANT, authority.grantId().value());
        } else {
            MDC.remove(MDC_GRANT);
        }
    }

    /** Enters the scope on this thread. Close it (try-with-resources) to restore what was there before. */
    public static AuthorityScope enter(InstanceAuthority authority) {
        return new AuthorityScope(java.util.Objects.requireNonNull(authority, "authority"));
    }

    /** The authority of the work on this thread, if any. */
    public static Optional<InstanceAuthority> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        restore(previous, previousSubject, previousGrant, previousInstance);
    }

    private static void restore(InstanceAuthority previous, Object subject, Object grant, Object instance) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
        put(MDC_SUBJECT, subject);
        put(MDC_GRANT, grant);
        put(MDC_INSTANCE, instance);
    }

    private static void put(String key, Object value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    // ---- carrying the scope into other threads -----------------------------------------------------------------------

    /** A runnable that runs under the authority of the thread that wrapped it. Without one, it runs as is. */
    public static Runnable wrap(Runnable task) {
        InstanceAuthority captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (AuthorityScope ignored = enter(captured)) {
                task.run();
            }
        };
    }

    /** A callable that runs under the authority of the thread that wrapped it. */
    public static <T> Callable<T> wrapCall(Callable<T> task) {
        InstanceAuthority captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (AuthorityScope ignored = enter(captured)) {
                return task.call();
            }
        };
    }

    /** A supplier that runs under the authority of the thread that wrapped it (for CompletableFuture stages). */
    public static <T> Supplier<T> wrapGet(Supplier<T> task) {
        InstanceAuthority captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (AuthorityScope ignored = enter(captured)) {
                return task.get();
            }
        };
    }

    /** An executor whose tasks run under the authority of the thread that submits them. */
    public static Executor wrapExecutor(Executor delegate) {
        return task -> delegate.execute(wrap(task));
    }
}
