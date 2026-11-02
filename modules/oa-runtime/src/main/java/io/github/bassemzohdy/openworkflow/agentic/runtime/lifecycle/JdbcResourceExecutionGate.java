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
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.IdempotentAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditReceipt;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

/**
 * Explicitly constructed internal JDBC entry gate; NOT a CDI admission bridge or a graph adapter. Only its own
 * freshly inserted, acknowledged commit (including connection close and ambient transaction resume) reaches a
 * callback. A historical row or commit readback never does. The caller supplies a trusted composite live fence,
 * never one from HTTP/workflow fields. No identity/PDP/task/target/lease implementation or default allowance exists
 * here. Production adoption must supply all of those, mandatory audit and governed node/output propagation.
 *
 * <p>Attempts are bounded with no queue. The optional async entry uses gate-owned workers with an additional shared
 * classloader-wide bound; its callback is
 * still synchronous and must not detach work. Close invalidates promptly, but a running
 * attempt retains its slot and fence until the actual synchronous worker exits, even if JDBC ignores interruption.
 * A returned future, completion stage or JDK publisher is refused and quarantines that gate slot permanently: its
 * completion or cancellation does not prove that detached physical work stopped. This cannot detect hidden work
 * behind other returned values or stop a side effect already in flight; callers must still be trusted and synchronous.
 * Callback failure, cancellation, post-commit refusal and uncertain replies retain the irreversible entry. They must
 * never be retried as a fresh call/epoch. The result and retained facts are data, not execution capabilities.
 */
public final class JdbcResourceExecutionGate implements AutoCloseable {
    /** Upper bound for managed async workers across gate instances in this classloader. */
    public static final int PROCESS_WORKER_LIMIT = 64;
    private static final Semaphore PROCESS_WORKERS = new Semaphore(PROCESS_WORKER_LIMIT);
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
    private final ResourceEntryAuditSource entryAudit;
    private final IdempotentAuditSink entryAuditSink;
    private final Semaphore workerSlots;
    private final ThreadFactory workerFactory;
    private final Set<Attempt> active = new HashSet<>();
    private boolean closed;

    /** Plain, non-JTA data source only. Managed sources must use the constructor with their transaction manager. */
    public JdbcResourceExecutionGate(DataSource data, int maxInFlight) { this(data, null, maxInFlight, IoBounds.defaults()); }
    public JdbcResourceExecutionGate(DataSource data, TransactionManager transactions, int maxInFlight) {
        this(data, transactions, maxInFlight, IoBounds.defaults());
    }
    public JdbcResourceExecutionGate(DataSource data, int maxInFlight, IoBounds io) { this(data, null, maxInFlight, io); }
    public JdbcResourceExecutionGate(DataSource data, TransactionManager transactions, int maxInFlight, IoBounds io) {
        this(data, transactions, maxInFlight, io, null);
    }
    /** Explicitly retain entry audit drafts atomically; delivery and authorization are separate responsibilities. */
    public JdbcResourceExecutionGate(DataSource data, TransactionManager transactions, int maxInFlight, IoBounds io,
                                     ResourceEntryAuditSource entryAudit) {
        this(data, transactions, maxInFlight, io, entryAudit, null);
    }
    /** Explicit co-located audit delivery before callback; sink and central tables must share this database. */
    public JdbcResourceExecutionGate(DataSource data, TransactionManager transactions, int maxInFlight, IoBounds io,
                                     ResourceEntryAuditSource entryAudit, IdempotentAuditSink entryAuditSink) {
        this.data = Objects.requireNonNull(data); this.transactions = transactions;
        this.io = Objects.requireNonNull(io);
        this.entryAudit = entryAudit;
        if (entryAuditSink != null && entryAudit == null) throw new IllegalArgumentException("audit producer is required");
        this.entryAuditSink = entryAuditSink;
        if (maxInFlight < 1 || maxInFlight > 1024) throw new IllegalArgumentException("entry bound must be 1..1024");
        limit = maxInFlight;
        workerSlots = new Semaphore(maxInFlight);
        workerFactory = Thread.ofVirtual().name("resource-entry-", 0).inheritInheritableThreadLocals(false).factory();
    }
    public synchronized int activeCount() { return active.size(); }
    /** Slots reserved by gate-owned workers, including workers that have not yet started or have not exited. */
    public int reservedWorkerCount() { return limit - workerSlots.availablePermits(); }
    /** Shared managed-worker slots in use or permanently quarantined in this classloader. */
    public static int reservedProcessWorkerCount() { return PROCESS_WORKER_LIMIT - PROCESS_WORKERS.availablePermits(); }
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

    /**
     * Explicit candidate async transport around a synchronous governed callback. No queue or caller-supplied executor
     * is used. Cancellation closes the attempt without interrupting the worker and cannot stop an in-flight effect;
     * capacity is retained until the
     * gate-owned worker actually exits. Managed async workers also share a bounded classloader-wide pool. A detected
     * detached result quarantines that shared slot permanently. Synchronous callers and other classloaders are outside
     * this bound; this candidate is not installed in the application.
     */
    public <T> Future<Result<T>> executeGovernedAsync(ResourceCallBinding binding, ResourceExecutionEntry entry,
            UUID executor, CredentialFence trustedFence, Function<Running, T> callback) {
        return executeGovernedAsync(binding, entry, executor, trustedFence, callback, () -> { });
    }

    /** The trusted termination action runs after the synchronous worker returns, including on cancellation. */
    public <T> Future<Result<T>> executeGovernedAsync(ResourceCallBinding binding, ResourceExecutionEntry entry,
            UUID executor, CredentialFence trustedFence, Function<Running, T> callback, Runnable onTermination) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(onTermination, "onTermination");
        var attempt = openGoverned(binding, entry, executor, trustedFence);
        var result = new AsyncResult<T>(attempt);
        if (!workerSlots.tryAcquire()) {
            attempt.close();
            result.outcome.completeExceptionally(terminate(onTermination, new RefusedException("worker capacity unavailable")));
            return result;
        }
        if (!PROCESS_WORKERS.tryAcquire()) {
            workerSlots.release();
            attempt.close();
            result.outcome.completeExceptionally(terminate(onTermination, new RefusedException("process worker capacity unavailable")));
            return result;
        }
        attempt.managed = true; // Keep the gate slot through termination cleanup, not just callback return.
        try {
            workerFactory.newThread(() -> {
                Result<T> value = null;
                Throwable failure = null;
                try {
                    value = attempt.executeGoverned(callback);
                } catch (Throwable caught) {
                    failure = caught;
                } finally {
                    failure = terminate(onTermination, failure);
                    workerSlots.release();
                    if (attempt.phase.get() != 3) {
                        retire(attempt);
                        PROCESS_WORKERS.release();
                    }
                }
                if (failure == null) result.outcome.complete(value);
                else {
                    result.outcome.completeExceptionally(failure);
                    if (failure instanceof Error fatal) throw fatal;
                }
            }).start();
        } catch (RuntimeException | Error failure) {
            attempt.close();
            var terminal = terminate(onTermination, failure);
            retire(attempt);
            workerSlots.release();
            PROCESS_WORKERS.release();
            if (terminal instanceof Error fatal) throw fatal;
            throw (RuntimeException) terminal;
        }
        return result;
    }

    private static Throwable terminate(Runnable action, Throwable failure) {
        try { action.run(); return failure; }
        catch (Throwable cleanup) { return combine(failure, cleanup); }
    }

    /** The caller can cancel or wait, but cannot fabricate completion or access the private attempt. */
    @JsonSerialize(using = TransientControlSerializer.class)
    private final class AsyncResult<T> implements Future<Result<T>> {
        private final Attempt attempt;
        private final CompletableFuture<Result<T>> outcome = new CompletableFuture<>();
        private AsyncResult(Attempt attempt) { this.attempt = attempt; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (outcome.isDone()) return false;
            attempt.close();
            return outcome.cancel(false);
        }
        @Override public boolean isCancelled() { return outcome.isCancelled(); }
        @Override public boolean isDone() { return outcome.isDone(); }
        @Override public Result<T> get() throws InterruptedException, ExecutionException { return outcome.get(); }
        @Override public Result<T> get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException { return outcome.get(timeout, unit); }
        @Override public String toString() { return "AsyncResourceResult[redacted]"; }
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
        /** Executor identity is retained data, not a capability; the handle remains the authority. */
        public UUID executor() { return attempt.executor; }
        public void requireCurrent() {
            if (attempt.phase.get() != 1) throw new RefusedException("run is not executing");
            attempt.check();
        }
        public void requireCurrent(ResourceExecutionEntry expected) {
            if (!attempt.entry.equals(Objects.requireNonNull(expected))) throw new RefusedException("run entry differs");
            requireCurrent();
        }
        /** Also binds work to the executor of this exact private-minted attempt. */
        public void requireCurrent(ResourceExecutionEntry expected, UUID expectedExecutor) {
            if (!attempt.executor.equals(Objects.requireNonNull(expectedExecutor))) throw new RefusedException("run executor differs");
            requireCurrent(expected);
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
        private volatile boolean managed;
        // 0 unstarted, 1 executing, 2 terminated, 3 quarantined after an observable detached result.
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
            boolean quarantine = false;
            try {
                check();
                Write write = isolated(true, c -> append(c, binding, entry, executor));
                check(); // only after commit, connection cleanup AND ambient resume, never under the registry lock.
                if (!write.fresh()) return new Result<>(Kind.RETAINED, write.fact(), null);
                if (entryAuditSink != null) {
                    deliverEntryAudit(entry.scope());
                    check(); // central audit may block; authority must still be current before callback.
                }
                T value = callback.apply(running);
                if (value instanceof Future<?> || value instanceof CompletionStage<?> || value instanceof Flow.Publisher<?>) {
                    quarantine = true;
                    throw new RefusedException("asynchronous callback result cannot prove work termination");
                }
                check(); // no private callback output after blocking work loses current permission.
                return new Result<>(Kind.CALLED, write.fact(), value);
            } finally {
                invalid.set(true);
                fence.set(null);
                phase.set(quarantine ? 3 : 2);
                if (!quarantine && !managed) retire(this);
            }
        }
        @Override public void close() {
            invalid.set(true);
            if (phase.compareAndSet(0, 2)) { fence.set(null); if (!managed) retire(this); }
        }
        @Override public String toString() { return "ResourceEntryAttempt[redacted]"; }
    }

    /** Retained DATA only, including after cancellation/expiry; presence/absence is never a fresh acknowledgement. */
    public Optional<EntryFact> find(ResourceCallScope scope) {
        Objects.requireNonNull(scope);
        return isolated(false, c -> read(c, scope));
    }
    /** Reads and validates original retained data only; it neither appends centrally nor authorizes a callback. */
    public Optional<ResourceEntryAuditSource.Retained> findEntryAudit(ResourceCallScope scope) {
        Objects.requireNonNull(scope);
        if (entryAudit == null) throw new IllegalStateException("entry audit producer is not configured");
        return isolated(false, c -> {
            var fact = read(c, scope);
            return fact.isEmpty() ? Optional.empty() : entryAudit.read(c, fact.get());
        });
    }
    /** Deliver the original source and verify the co-located central binding; a receipt alone is not authority. */
    public AuditReceipt deliverEntryAudit(ResourceCallScope scope) {
        Objects.requireNonNull(scope);
        if (entryAuditSink == null) throw new IllegalStateException("entry audit delivery is not configured");
        java.util.function.Supplier<AuditReceipt> delivery = () -> {
            var retained = findEntryAudit(scope).orElseThrow(() -> new IllegalStateException("entry audit source missing"));
            var receipt = Objects.requireNonNull(entryAuditSink.appendOnce(retained.source(), retained.draft()), "audit receipt");
            receipt.requireMatches(retained.source(), retained.draft());
            isolated(false, c -> {
                var fact = read(c, scope).orElseThrow(() -> new IllegalStateException("entry missing"));
                if (!retained.equals(entryAudit.read(c, fact).orElseThrow()))
                    throw new IllegalStateException("entry audit source changed");
                try (var q = c.prepareStatement("""
                        select 1 from audit.source_binding where source_key=? and event_id=? and chain_id=?
                          and chain_sequence=? and hash=? and content_fingerprint=?
                        """)) {
                    q.setString(1, receipt.source().key()); q.setString(2, receipt.eventId().value());
                    q.setString(3, receipt.chainId()); q.setLong(4, receipt.chainSequence());
                    q.setString(5, receipt.hash()); q.setString(6, receipt.contentFingerprint());
                    try (var r = q.executeQuery()) {
                        if (!r.next()) throw new IllegalStateException("central entry audit binding missing");
                    }
                }
                return null;
            });
            return receipt;
        };
        return transactions == null ? delivery.get() : Transactions.inSuspendedTransaction(transactions, delivery);
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
        if (inserted == 1 && entryAudit != null) entryAudit.captureFresh(c, fact);
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
        // A stricter role/session limit is never widened.
        Transactions.capPostgresTimeouts(c,(int)io.lock().toMillis(),(int)io.statement().toMillis(),(int)io.network().toMillis());
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
            oldNetwork = Transactions.setClientNetworkTimeout(c,(int)io.network().toMillis());
            networkChanged = true;
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
                    if (networkChanged) Transactions.restoreClientNetworkTimeout(c,oldNetwork);
                } catch (Throwable reset) { failure = combine(failure,reset); discard = true; }
                if (discard) try { Transactions.abort(c); } catch (Throwable abort) { failure = combine(failure,abort); }
                try { c.close(); } catch (Throwable cleanup) {
                    failure = combine(failure,cleanup);
                    try { Transactions.abort(c); } catch (Throwable abort) { failure = combine(failure,abort); }
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
