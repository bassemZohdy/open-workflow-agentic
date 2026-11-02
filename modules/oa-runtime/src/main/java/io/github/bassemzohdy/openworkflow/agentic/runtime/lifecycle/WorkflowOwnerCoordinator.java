package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.serverlessworkflow.impl.WorkflowContextData;
import jakarta.transaction.TransactionManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

/**
 * Explicit, bounded candidate for one process to acquire and renew workflow-owner generations. Not installed in
 * application startup or restore. The caller must establish that the SDK context belongs to this execution before
 * calling acquire; a database binding alone cannot prove provenance of an unbound stale context. Local state is not a
 * capability: every task and write must still use its live fence. A close or uncertain renewal revokes the context and
 * lets the database lease expire; it does not release the lease or stop an already-running external effect.
 */
public final class WorkflowOwnerCoordinator implements AutoCloseable {
    public enum State { STARTING, ACTIVE, LOST, CLOSED }
    private static final int MAX_RENEWAL_WORKERS = 4;
    private final Object monitor = new Object();
    private final JdbcWorkflowOwnerLease leases;
    private final TransactionManager transactions;
    private final WorkflowOwnerContextBindings bindings;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<Ulid, Session> sessions = new HashMap<>();
    private final int capacity;
    private boolean closed;

    public WorkflowOwnerCoordinator(DataSource data, TransactionManager transactions,
                                    WorkflowOwnerContextBindings bindings, int capacity) {
        this.leases = new JdbcWorkflowOwnerLease(Objects.requireNonNull(data));
        this.transactions = Objects.requireNonNull(transactions);
        this.bindings = Objects.requireNonNull(bindings);
        if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("owner capacity must be 1..1024");
        this.capacity = capacity;
        scheduler = new ScheduledThreadPoolExecutor(Math.min(capacity, MAX_RENEWAL_WORKERS), task -> {
            var thread = new Thread(task, "workflow-owner-renewal"); thread.setDaemon(true); return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /**
     * Binds exactly this context after the database acknowledges acquisition. A failed/uncertain acquisition does
     * not expose a claim; any possibly committed lease is left to expire. Duplicate local instance attempts refuse.
     */
    public Optional<Session> acquire(JdbcWorkflowOwnerLease.Binding binding, WorkflowContextData context, Duration ttl) {
        Objects.requireNonNull(binding); Objects.requireNonNull(context); long millis = ttlMillis(ttl);
        // The SDK identity is checked before any lease is acquired; the owner UUID is fresh for each attempt.
        var initial = new JdbcWorkflowOwnerLease.Claim(binding, UUID.randomUUID(), 1);
        WorkflowOwnerJpaFence.requireMatchingContext(context, initial);
        Session session;
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("owner coordinator is closed");
            if (sessions.containsKey(binding.instanceId())) return Optional.empty();
            if (sessions.size() >= capacity) throw new IllegalStateException("owner coordinator capacity exhausted");
            session = new Session(binding, context, initial.ownerId(), ttl);
            sessions.put(binding.instanceId(), session);
        }
        Optional<JdbcWorkflowOwnerLease.Lease> acquired;
        try {
            acquired = Transactions.inSuspendedTransaction(transactions, () -> leases.acquire(binding, session.ownerId, ttl));
        } catch (RuntimeException | Error failure) {
            abandonStarting(session); throw failure;
        }
        synchronized (monitor) {
            if (closed || session.state != State.STARTING) {
                sessions.remove(binding.instanceId(), session);
                session.state = State.CLOSED;
                return Optional.empty(); // An acknowledged but unused lease expires without authorizing work.
            }
            if (acquired.isEmpty()) {
                sessions.remove(binding.instanceId(), session); session.state = State.LOST; return Optional.empty();
            }
            session.claim = acquired.get().claim();
            try {
                bindings.bind(context, session.claim);
                session.state = State.ACTIVE;
                long interval = Math.max(50, millis / 3);
                session.renewal = scheduler.scheduleWithFixedDelay(session::renew, interval, interval, TimeUnit.MILLISECONDS);
                return Optional.of(session);
            } catch (RuntimeException | Error failure) {
                bindings.revoke(context, session.claim);
                sessions.remove(binding.instanceId(), session); session.state = State.LOST;
                throw failure;
            }
        }
    }

    private void abandonStarting(Session session) {
        synchronized (monitor) {
            sessions.remove(session.binding.instanceId(), session);
            if (session.state == State.STARTING) session.state = State.LOST;
        }
    }

    public final class Session implements AutoCloseable {
        private final JdbcWorkflowOwnerLease.Binding binding;
        private final WorkflowContextData context;
        private final UUID ownerId;
        private final Duration ttl;
        private State state = State.STARTING;
        private JdbcWorkflowOwnerLease.Claim claim;
        private ScheduledFuture<?> renewal;
        private Session(JdbcWorkflowOwnerLease.Binding binding, WorkflowContextData context, UUID ownerId, Duration ttl) {
            this.binding = binding; this.context = context; this.ownerId = ownerId; this.ttl = ttl;
        }
        /** Local session state only; this is never a substitute for a fresh database fence. */
        public State state() { synchronized (monitor) { return state; } }
        public JdbcWorkflowOwnerLease.Claim claim() {
            synchronized (monitor) {
                if (state != State.ACTIVE) throw new IllegalStateException("owner session is not active");
                return claim;
            }
        }
        private void renew() {
            JdbcWorkflowOwnerLease.Claim current;
            synchronized (monitor) { if (state != State.ACTIVE) return; current = claim; }
            try {
                var result = Transactions.inSuspendedTransaction(transactions, () -> leases.renew(current, ttl));
                if (result.isEmpty()) lose();
            } catch (RuntimeException failure) {
                // A timeout can have committed a renewal. Fail closed locally; never reacquire on this context.
                lose();
            } catch (Error fatal) {
                lose(); throw fatal;
            }
        }
        private void lose() {
            synchronized (monitor) {
                if (state != State.ACTIVE) return;
                bindings.revoke(context, claim); state = State.LOST;
                sessions.remove(binding.instanceId(), this);
                if (renewal != null) renewal.cancel(false);
            }
        }
        /** Stops renewal and revokes local authority. The lease is not released before expiry. */
        @Override public void close() {
            synchronized (monitor) {
                if (state == State.CLOSED) return;
                if (claim != null) bindings.revoke(context, claim);
                state = State.CLOSED; sessions.remove(binding.instanceId(), this);
                if (renewal != null) renewal.cancel(false);
            }
        }
    }

    @Override public void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            for (var session : List.copyOf(sessions.values())) session.close();
        }
        scheduler.shutdown(); // In-flight JDBC work may finish, but a closed context stays revoked.
        // Data-source acquisition and network delay are not bounded by the PostgreSQL statement timeouts.
    }

    private static long ttlMillis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.compareTo(Duration.ofMillis(250)) < 0 || ttl.compareTo(Duration.ofSeconds(30)) > 0
                || ttl.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("lease TTL must be whole milliseconds from 250ms through 30s");
        return ttl.toMillis();
    }
}
