package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import jakarta.transaction.TransactionManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Explicit candidate task-and-target check for one entry and original durable allocation. The caller must supply a
 * trusted current approval check covering the full allocation, grant ancestry, task provenance and target; retained
 * allocation data alone is not approval. V78 is proposed and this is not installed in application startup.
 *
 * <p>Database time and the complete allocation are checked before and after potentially blocking approval I/O.
 * A check can become stale immediately afterward and cannot stop an external effect already in flight.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class ResourceTaskExecutionFence implements ResourceExecutionFence, AutoCloseable {
    @FunctionalInterface public interface CurrentApproval {
        void requireCurrent(ResourceExecutionEntry entry, AllocatedResourceTask allocation);
    }

    private final DataSource data;
    private final TransactionManager transactions;
    private final ResourceExecutionEntry entry;
    private final AllocatedResourceTask expected;
    private final CurrentApproval approval;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResourceTaskExecutionFence(DataSource data, TransactionManager transactions, ResourceExecutionEntry entry,
                                      AllocatedResourceTask expected, CurrentApproval approval) {
        this.data = Objects.requireNonNull(data, "data");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entry = Objects.requireNonNull(entry, "entry");
        this.expected = Objects.requireNonNull(expected, "expected");
        this.approval = Objects.requireNonNull(approval, "approval");
        if (!entry.scope().equals(expected.allocation().scope()))
            throw new IllegalArgumentException("task scope differs from entry");
    }

    @Override public void requireCurrent(ResourceExecutionEntry requested) {
        requireOpen();
        if (!entry.equals(requested)) throw refused("entry differs");
        try {
            requireAllocation();
            approval.requireCurrent(entry, expected);
            requireOpen();
            requireAllocation(); // Approval can block beyond the retained database deadline.
            requireOpen();
        } catch (JdbcResourceExecutionGate.RefusedException refused) {
            throw refused;
        } catch (RuntimeException failure) {
            var refused = refused("task or target check unavailable");
            refused.initCause(failure);
            throw refused;
        }
    }

    private void requireAllocation() {
        Transactions.inSuspendedTransaction(transactions, () -> Transactions.inTransaction(data, c -> {
            Transactions.capPostgresTimeouts(c, 2_000, 5_000, 5_000);
            var current = JdbcResourceTaskAllocations.read(c, entry.scope()).orElseThrow(() -> refused("task missing"));
            if (!expected.equals(current)) throw refused("task differs");
            try (var q = c.prepareStatement("select pg_catalog.clock_timestamp()")) {
                q.setQueryTimeout(5);
                try (var r = q.executeQuery()) {
                    if (!r.next() || !r.getTimestamp(1).toInstant().isBefore(current.deadline()))
                        throw refused("task deadline passed");
                }
            }
            return null;
        }, 6_000));
    }

    private void requireOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw refused("task context unavailable");
    }
    private static JdbcResourceExecutionGate.RefusedException refused(String reason) {
        return new JdbcResourceExecutionGate.RefusedException(reason);
    }
    @Override public void close() { closed.set(true); }
    @Override public String toString() { return "ResourceTaskExecutionFence[redacted]"; }
}
