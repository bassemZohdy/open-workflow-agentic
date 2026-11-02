package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Explicit candidate for recovering a guarded pending cancellation from the proposed V81 receipt.
 * Activated only by the reconciler's explicitly configured itest path: callers must establish single-owner
 * execution and deploy the complete receipt schema and its write fences before relying on this evidence. In
 * particular, checkpoint removal alone does not stop work already running on another replica.
 */
@Singleton
public class PendingCancellationReceiptRecovery {
    private final DataSource dataSource;
    private final InstanceStore store;
    private final Clock clock;

    @Inject
    public PendingCancellationReceiptRecovery(DataSource dataSource, InstanceStore store) {
        this(dataSource, store, Clock.systemUTC());
    }

    public PendingCancellationReceiptRecovery(DataSource dataSource, InstanceStore store, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Applies one exact receipt through the registry's normal transition, history, and outbox path.
     * Returns false when the row is missing, no longer awaiting cancellation, or lacks the expected receipt;
     * concurrent registry updates win through the store's optimistic version check.
     */
    public boolean recover(String tenant, Ulid instanceId, String applicationId) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(applicationId, "applicationId");
        var row = store.find(tenant, instanceId).orElse(null);
        if (row == null || row.status() != InstanceStatus.CANCEL_REQUESTED || row.engineInstanceId() == null)
            return false;
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("""
                     select 1 from task.pending_cancellation_receipt r
                     join task.instance i on i.id = r.instance_id
                     where i.id = ? and i.tenant = ? and i.status = 'CANCEL_REQUESTED'
                       and i.version = ? and i.engine_instance_id = r.engine_instance_id
                       and i.definition_id = r.definition_id and i.definition_version = r.definition_version
                       and r.application_id = ?
                       and not exists (select 1 from public.workflowinstanceentity w
                                       where w.applicationid = r.application_id and w.instanceid = r.engine_instance_id)
                     """)) {
            query.setQueryTimeout(5);
            query.setString(1, instanceId.value());
            query.setString(2, tenant);
            query.setLong(3, row.version());
            query.setString(4, applicationId);
            try (var result = query.executeQuery()) { if (!result.next()) return false; }
        } catch (SQLException unavailable) {
            throw new StoreUnavailableException("pending cancellation receipt recovery unavailable", unavailable);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            store.replace(row.successor(now, InstanceStatus.CANCELLED, row.engineInstanceId(), row.startAttempts(),
                    row.lastError(), null), row.version(), "engine.receipt.CANCELLED", InstanceService.RUNTIME_ACTOR);
            return true;
        } catch (StaleVersionException changed) {
            return false;
        }
    }
}
