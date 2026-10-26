package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallScope;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
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
    /** Reject accidental checkpoint/config serialization even when empty-bean serialization is enabled. */
    public static final class TransientControlSerializer extends JsonSerializer<Object> {
        @Override public void serialize(Object value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            throw JsonMappingException.from(generator, "Transient execution control cannot be serialized");
        }
    }
    /** Explicit millisecond bounds for owned JDBC operations; acquisition remains the data source's responsibility. */
    public record IoBounds(Duration lock, Duration statement, Duration network) {
        public IoBounds {
            for (Duration value : new Duration[]{lock, statement, network}) {
                Objects.requireNonNull(value, "JDBC bound");
                if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(5)) > 0
                        || value.getNano() % 1_000_000 != 0)
                    throw new IllegalArgumentException("JDBC bounds must be whole milliseconds in 1ms..5min");
            }
            if (lock.compareTo(statement) > 0 || statement.compareTo(network) >= 0)
                throw new IllegalArgumentException("JDBC bounds require lock <= statement < network");
        }
        public static IoBounds defaults() { return new IoBounds(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30)); }
    }
    /**
     * Immutable association of binding DATA with the trusted fence. This is not verified admission or permission:
     * the composition root must create its delegate from the SAME opaque admitted credential using the 4-arg live
     * identity check. No arbitrary workflow/HTTP field may supply a fence. Construction must stay root-restricted.
     */
    @JsonSerialize(using = TransientControlSerializer.class)
    public static final class CredentialFence implements ResourceExecutionFence {
        private final ResourceCallBinding binding;
        private final ResourceExecutionFence delegate;
        private CredentialFence(ResourceCallBinding binding, ResourceExecutionFence delegate) {
            this.binding = Objects.requireNonNull(binding); this.delegate = Objects.requireNonNull(delegate);
        }
        public static CredentialFence pair(ResourceCallBinding binding, ResourceExecutionFence trustedFence) {
            return new CredentialFence(binding, trustedFence);
        }
        @Override public void requireCurrent(ResourceExecutionEntry entry) { delegate.requireCurrent(entry); }
        @Override public String toString() { return "CredentialFence[redacted]"; }
    }
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
    private final IoBounds io;
    private static final Executor DIRECT = Runnable::run;
    private final Set<Attempt> active = new HashSet<>();
    private boolean closed;

    /** Plain, non-JTA data source only. Managed sources must use the constructor with their transaction manager. */
    public JdbcResourceExecutionGate(DataSource data, int maxInFlight) { this(data, null, maxInFlight, IoBounds.defaults()); }
    public JdbcResourceExecutionGate(DataSource data, TransactionManager transactions, int maxInFlight) {
        this(data, transactions, maxInFlight, IoBounds.defaults());
    }
    public JdbcResourceExecutionGate(DataSource data, int maxInFlight, IoBounds io) { this(data, null, maxInFlight, io); }
    public JdbcResourceExecutionGate(DataSource data, TransactionManager transactions, int maxInFlight, IoBounds io) {
        this.data = Objects.requireNonNull(data); this.transactions = transactions;
        this.io = Objects.requireNonNull(io);
        if (maxInFlight < 1 || maxInFlight > 1024) throw new IllegalArgumentException("entry bound must be 1..1024");
        limit = maxInFlight;
    }
    public synchronized int activeCount() { return active.size(); }
    /** Legacy synchronous component callback. Production node composition must use openGoverned/executeGoverned. */
    public Attempt open(ResourceCallBinding binding, ResourceExecutionEntry entry, UUID executor,
                        ResourceExecutionFence trustedFence) {
        return openInternal(binding, entry, executor, trustedFence, false);
    }
    public Attempt openGoverned(ResourceCallBinding binding, ResourceExecutionEntry entry, UUID executor,
                                CredentialFence trustedFence) {
        Objects.requireNonNull(trustedFence);
        if (!Objects.requireNonNull(binding).equals(trustedFence.binding)) throw new RefusedException("fence credential differs");
        return openInternal(binding, entry, executor, trustedFence, true);
    }
    private synchronized Attempt openInternal(ResourceCallBinding binding, ResourceExecutionEntry entry, UUID executor,
                                             ResourceExecutionFence trustedFence, boolean governed) {
        Objects.requireNonNull(binding); Objects.requireNonNull(entry); Objects.requireNonNull(executor);
        Objects.requireNonNull(trustedFence);
        if (!binding.scope().equals(entry.scope())) throw new RefusedException("binding scope differs");
        if (closed) throw new RefusedException("gate closed");
        if (active.size() >= limit) throw new RefusedException("entry capacity exhausted");
        Attempt attempt = new Attempt(binding, entry, executor, trustedFence, governed); active.add(attempt); return attempt;
    }

    /**
     * Minted only inside a fresh governed callback. Capture in per-run node closures, never state/config/metadata.
     * requireCurrent checks the SAME attempt's live fence and invalidation on every invocation, across threads.
     * Closing it invalidates the run, but never releases the slot before its synchronous callback really terminates.
     * entry() remains historical DATA after termination; it cannot revive the control handle.
     */
    @JsonSerialize(using = TransientControlSerializer.class)
    public final class Running implements AutoCloseable {
        private final Attempt attempt;
        private Running(Attempt attempt) { this.attempt = attempt; }
        public ResourceExecutionEntry entry() { return attempt.entry; }
        public void requireCurrent() {
            if (attempt.phase.get() != 1) throw new RefusedException("run is not executing");
            attempt.check();
        }
        public void requireCurrent(ResourceExecutionEntry expected) {
            if (!attempt.entry.equals(Objects.requireNonNull(expected))) throw new RefusedException("run entry differs");
            requireCurrent();
        }
        @Override public void close() { attempt.close(); }
        @Override public String toString() { return "RunningResourceExecution[redacted]"; }
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
        private final boolean governed;
        private final Running running;
        private final AtomicReference<ResourceExecutionFence> fence;
        private final AtomicBoolean invalid = new AtomicBoolean();
        // 0 unstarted, 1 executing, 2 actually terminated (closing phase 1 must not release its slot).
        private final AtomicInteger phase = new AtomicInteger();
        private Attempt(ResourceCallBinding binding, ResourceExecutionEntry entry, UUID executor,
                        ResourceExecutionFence fence, boolean governed) {
            this.binding = binding; this.entry = entry; this.executor = executor;
            this.governed = governed; this.running = new Running(this);
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
            if (governed) throw new RefusedException("governed callback requires running control");
            return executeInternal(ignored -> callback.apply(entry));
        }
        /** Same synchronous/actual-termination contract as execute; only fresh acknowledged entry exposes Running. */
        public <T> Result<T> executeGoverned(Function<Running, T> callback) {
            Objects.requireNonNull(callback);
            if (!governed) throw new RefusedException("credential-paired fence required");
            return executeInternal(callback);
        }
        private <T> Result<T> executeInternal(Function<Running, T> callback) {
            if (!phase.compareAndSet(0, 1)) throw new RefusedException("attempt already consumed");
            try {
                check();
                Write write = isolated(true, c -> append(c, binding, entry, executor));
                check(); // only after commit, connection cleanup AND ambient resume, never under the registry lock.
                if (!write.fresh()) return new Result<>(Kind.RETAINED, write.fact(), null);
                T value = callback.apply(running);
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
    private void configureOwnedTransaction(Connection c) throws SQLException {
        // A stricter role/session limit is never widened. PostgreSQL reports these GUCs as interval-compatible text.
        String cap = "pg_catalog.set_config(?,least(coalesce(nullif(extract(epoch from "
                + "pg_catalog.current_setting(?)::pg_catalog.interval)*1000,0),?),?)::bigint::text||'ms',true)";
        try (var q = c.prepareStatement("select " + cap + "," + cap + "," + cap)) {
            q.setQueryTimeout((int)((io.statement().toMillis()+999)/1000));
            String[] names = {"lock_timeout", "statement_timeout", "idle_in_transaction_session_timeout"};
            long[] values = {io.lock().toMillis(), io.statement().toMillis(), io.network().toMillis()};
            for (int i=0;i<names.length;i++) {
                q.setString(i*4+1,names[i]); q.setString(i*4+2,names[i]);
                q.setLong(i*4+3,values[i]); q.setLong(i*4+4,values[i]);
            }
            q.execute();
        }
    }
    private static Throwable combine(Throwable primary, Throwable secondary) {
        if (primary == null || primary == secondary) return primary == null ? secondary : primary;
        if (secondary instanceof Error && !(primary instanceof Error)) {
            secondary.addSuppressed(primary); return secondary;
        }
        primary.addSuppressed(secondary); return primary;
    }
    /** Finish every owned resource before returning an acknowledgement; a cleanup/resume failure cannot dispatch. */
    private <T> T isolated(boolean mutation, Work<T> work) {
        Transaction ambient = null;
        Connection c = null;
        boolean commitAttempted = false;
        boolean ownsTransaction = false;
        boolean rollbackFailed = false;
        boolean networkChanged = false, autoChanged = false, isolationChanged = false;
        int oldNetwork = 0;
        Integer oldIsolation = null;
        T value = null;
        Throwable failure = null;
        try {
            if (transactions != null) ambient = transactions.suspend();
            c = data.getConnection();
            if (!c.getAutoCommit()) throw new RefusedException("connection already has a transaction");
            oldNetwork = c.getNetworkTimeout();
            int network = (int)io.network().toMillis();
            if (oldNetwork > 0) network = Math.min(network,oldNetwork);
            networkChanged = true; c.setNetworkTimeout(DIRECT,network);
            oldIsolation = c.getTransactionIsolation();
            autoChanged = true; c.setAutoCommit(false); ownsTransaction = true;
            isolationChanged = true; c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            configureOwnedTransaction(c);
            value = work.run(c);
            commitAttempted = true;
            c.commit();
        } catch (Throwable error) {
            failure = error;
            if (c != null && ownsTransaction) try { c.rollback(); } catch (Throwable rollback) {
                rollbackFailed = true; failure = combine(failure,rollback);
            }
        } finally {
            if (c != null) {
                boolean discard = rollbackFailed;
                if (!discard) try {
                    if (isolationChanged) c.setTransactionIsolation(oldIsolation);
                    // Never switch to auto-commit after a failed rollback: it could commit uncertain pending work.
                    if (autoChanged) c.setAutoCommit(true);
                    if (networkChanged) c.setNetworkTimeout(DIRECT,oldNetwork);
                } catch (Throwable reset) { failure = combine(failure,reset); discard = true; }
                if (discard) try { c.abort(DIRECT); } catch (Throwable abort) { failure = combine(failure,abort); }
                try { c.close(); } catch (Throwable cleanup) {
                    failure = combine(failure,cleanup);
                    try { c.abort(DIRECT); } catch (Throwable abort) { failure = combine(failure,abort); }
                }
            }
            if (ambient != null) try { transactions.resume(ambient); } catch (Throwable resume) {
                failure = combine(failure,resume);
            }
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure != null) {
            if (mutation && (commitAttempted || rollbackFailed)) throw new CommitUncertainException(failure);
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new StoreUnavailableException("resource execution storage unavailable", failure);
        }
        return value;
    }
}
