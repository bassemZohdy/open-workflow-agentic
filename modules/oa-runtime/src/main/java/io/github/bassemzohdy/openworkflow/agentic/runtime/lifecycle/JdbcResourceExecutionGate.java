package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Explicitly constructed internal JDBC entry gate; NOT a CDI admission bridge or a graph adapter. Only its own
 * freshly inserted, acknowledged commit (including connection close and ambient transaction resume) reaches a
 * callback. A historical row or commit readback never does. The caller supplies a trusted composite live fence,
 * never one from HTTP/workflow fields. No identity/PDP/task/target/lease implementation or default allowance exists
 * here. Production adoption must supply all of those, mandatory audit and governed node/output propagation.
 *
 * <p>Attempts are bounded with no queue or internally spawned workers. Close invalidates promptly, but a running
 * attempt retains its slot and fence until the actual synchronous worker exits, even if JDBC ignores interruption.
 * Callback failure, cancellation, post-commit refusal and uncertain replies retain the irreversible entry. They must
 * never be retried as a fresh call/epoch. The result and retained facts are data, not execution capabilities.
 */
public final class JdbcResourceExecutionGate implements AutoCloseable {
    public record EntryFact(ResourceExecutionEntry entry, String firstBindingKey, UUID enteringExecutor,
                            Instant enteredAt) {
        public EntryFact {
            Objects.requireNonNull(entry); Objects.requireNonNull(firstBindingKey);
            Objects.requireNonNull(enteringExecutor); Objects.requireNonNull(enteredAt);
        }
    }
    public enum Kind { CALLED, RETAINED }
    public record Result<T>(Kind kind, EntryFact fact, T value) {
        @Override public String toString() { return "ResourceExecutionResult[kind=" + kind + ", value=redacted]"; }
    }
    /** A write may have committed; reconcile the SAME call, never authorize another callback from readback. */
    public static final class CommitUncertainException extends RuntimeException {
        CommitUncertainException(Throwable cause) { super("resource entry acknowledgement is uncertain", cause); }
    }
    public static final class RefusedException extends IllegalStateException {
        RefusedException(String reason) { super("resource execution refused: " + reason); }
    }
    private record Write(EntryFact fact, boolean fresh) { }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private final DataSource data;
    private final TransactionManager transactions;
    private final int limit;
    private final Set<Attempt> active = new HashSet<>();
    private boolean closed;

    /** Plain, non-JTA data source only. Managed sources must use the constructor with their transaction manager. */
    public JdbcResourceExecutionGate(DataSource data, int maxInFlight) { this(data, null, maxInFlight); }
    public JdbcResourceExecutionGate(DataSource data, TransactionManager transactions, int maxInFlight) {
        this.data = Objects.requireNonNull(data); this.transactions = transactions;
        if (maxInFlight < 1 || maxInFlight > 1024) throw new IllegalArgumentException("entry bound must be 1..1024");
        limit = maxInFlight;
    }
    public synchronized int activeCount() { return active.size(); }
    public synchronized Attempt open(ResourceCallBinding binding, ResourceExecutionEntry entry, UUID executor,
                                      ResourceExecutionFence trustedFence) {
        Objects.requireNonNull(binding); Objects.requireNonNull(entry); Objects.requireNonNull(executor);
        Objects.requireNonNull(trustedFence);
        if (!binding.scope().equals(entry.scope())) throw new RefusedException("binding scope differs");
        if (closed) throw new RefusedException("gate closed");
        if (active.size() >= limit) throw new RefusedException("entry capacity exhausted");
        Attempt attempt = new Attempt(binding, entry, executor, trustedFence); active.add(attempt); return attempt;
    }
    private synchronized void retire(Attempt attempt) { active.remove(attempt); }
    @Override public void close() {
        List<Attempt> snapshot;
        synchronized (this) { closed = true; snapshot = List.copyOf(active); }
        snapshot.forEach(Attempt::close);
    }

    /** Private-minted one-shot control handle; never serialize into graph state, config, checkpoints or a ThreadLocal. */
    public final class Attempt implements AutoCloseable {
        private final ResourceCallBinding binding;
        private final ResourceExecutionEntry entry;
        private final UUID executor;
        private final AtomicReference<ResourceExecutionFence> fence;
        private final AtomicBoolean invalid = new AtomicBoolean();
        // 0 unstarted, 1 executing, 2 actually terminated (closing phase 1 must not release its slot).
        private final AtomicInteger phase = new AtomicInteger();
        private Attempt(ResourceCallBinding binding, ResourceExecutionEntry entry, UUID executor,
                        ResourceExecutionFence fence) {
            this.binding = binding; this.entry = entry; this.executor = executor;
            this.fence = new AtomicReference<>(fence);
        }
        private void requireOpen() {
            if (invalid.get() || Thread.currentThread().isInterrupted()) throw new RefusedException("attempt closed or interrupted");
        }
        private void check() {
            requireOpen();
            var current = fence.get();
            if (current == null) throw new RefusedException("fence unavailable");
            current.requireCurrent(entry);
            requireOpen(); // fence/PDP/audit may block; a concurrent close never releases work on return.
        }
        /**
         * Callback work must finish synchronously before returning; it must not return a completion stage or launch
         * detached workers. Async adapters need a separately reviewed actual-termination protocol. No arbitrary
         * returned object is interpreted as termination of hidden asynchronous work.
         */
        public <T> Result<T> execute(Function<ResourceExecutionEntry, T> callback) {
            Objects.requireNonNull(callback);
            if (!phase.compareAndSet(0, 1)) throw new RefusedException("attempt already consumed");
            try {
                check();
                Write write = isolated(true, c -> append(c, binding, entry, executor));
                check(); // only after commit, connection cleanup AND ambient resume, never under the registry lock.
                if (!write.fresh()) return new Result<>(Kind.RETAINED, write.fact(), null);
                T value = callback.apply(entry);
                check(); // no private callback output after blocking work loses current permission.
                return new Result<>(Kind.CALLED, write.fact(), value);
            } finally { invalid.set(true); phase.set(2); fence.set(null); retire(this); }
        }
        @Override public void close() {
            invalid.set(true);
            if (phase.compareAndSet(0, 2)) { fence.set(null); retire(this); }
        }
        @Override public String toString() { return "ResourceEntryAttempt[redacted]"; }
    }

    /** Retained DATA only, including after cancellation/expiry; presence/absence is never a fresh acknowledgement. */
    public Optional<EntryFact> find(ResourceCallScope scope) {
        Objects.requireNonNull(scope);
        return isolated(false, c -> read(c, scope));
    }
    private Write append(Connection c, ResourceCallBinding binding, ResourceExecutionEntry entry, UUID executor)
            throws SQLException {
        var retainedBinding = JdbcResourceCallBindings.read(c, binding.issuer(), binding.tokenId());
        if (retainedBinding.isEmpty() || !binding.equals(retainedBinding.get())) throw new RefusedException("exact binding missing");
        var before = read(c, entry.scope());
        if (before.isPresent()) return retained(before.get(), entry);
        int inserted;
        try (var q = c.prepareStatement("""
                insert into task.resource_execution(call_key,entry_key,contract_fingerprint,execution_epoch,
                    first_binding_key,entering_executor) values(?,?,?,?,?,?) on conflict(call_key) do nothing
                """)) {
            q.setString(1, entry.callKey()); q.setString(2, entry.fingerprint());
            q.setString(3, entry.scope().fingerprint()); q.setLong(4, entry.epoch());
            q.setString(5, binding.key()); q.setObject(6, executor); inserted = q.executeUpdate();
        }
        var fact = read(c, entry.scope()).orElseThrow(() -> new RefusedException("entry not retained"));
        if (!fact.entry().equals(entry)) throw new RefusedException("retained epoch differs");
        return new Write(fact, inserted == 1);
    }
    private static Write retained(EntryFact fact, ResourceExecutionEntry entry) {
        if (!fact.entry().equals(entry)) throw new RefusedException("retained epoch differs");
        return new Write(fact, false);
    }
    private static Optional<EntryFact> read(Connection c, ResourceCallScope expected) throws SQLException {
        try (var q = c.prepareStatement("""
                select c.*, o.occurrence_key, e.entry_key, e.contract_fingerprint, e.execution_epoch,
                       e.first_binding_key, e.entering_executor, e.entered_at
                from task.resource_execution e join task.resource_call c on c.call_key=e.call_key
                left join task.resource_occurrence o on o.call_key=c.call_key where e.call_key=?
                """)) {
            q.setString(1, expected.key());
            try (var r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var scope = JdbcResourceCallBindings.scope(r);
                if (!expected.equals(scope)) throw new RefusedException("full retained scope differs");
                var entry = new ResourceExecutionEntry(scope, r.getLong("execution_epoch"));
                if (!entry.fingerprint().equals(r.getString("entry_key"))
                        || !scope.fingerprint().equals(r.getString("contract_fingerprint")))
                    throw new RefusedException("retained entry differs");
                return Optional.of(new EntryFact(entry, r.getString("first_binding_key"),
                        r.getObject("entering_executor", UUID.class), r.getTimestamp("entered_at").toInstant()));
            }
        }
    }
    /** Finish every owned resource before returning an acknowledgement; a cleanup/resume failure cannot dispatch. */
    private <T> T isolated(boolean mutation, Work<T> work) {
        Transaction ambient = null;
        Connection c = null;
        boolean commitAttempted = false;
        boolean ownsTransaction = false;
        T value = null;
        Throwable failure = null;
        try {
            if (transactions != null) ambient = transactions.suspend();
            c = data.getConnection();
            if (!c.getAutoCommit()) throw new RefusedException("connection already has a transaction");
            c.setAutoCommit(false); ownsTransaction = true;
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            value = work.run(c);
            commitAttempted = true;
            c.commit();
        } catch (Throwable error) {
            failure = error;
            if (c != null && ownsTransaction) try { c.rollback(); } catch (Throwable rollback) {
                if (failure != rollback) failure.addSuppressed(rollback);
            }
        } finally {
            if (c != null) try { c.close(); } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            if (ambient != null) try { transactions.resume(ambient); } catch (Throwable resume) {
                if (failure == null) failure = resume; else failure.addSuppressed(resume);
            }
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure != null) {
            if (mutation && commitAttempted) throw new CommitUncertainException(failure);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new StoreUnavailableException("resource execution storage unavailable", failure);
        }
        return value;
    }
}
