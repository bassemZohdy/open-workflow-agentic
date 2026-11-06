package io.github.bassemzohdy.openworkflow.agentic.runtime.execution;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AllocatedResourceTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.IdempotentAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.LiveIdentityResourceFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.LiveResearchPolicyFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.ResearchAgent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.CompositeExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResourceTaskExecutionFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResourceEntryAuditSource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowOwnerResourceFence;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResearchOutcomes;
import jakarta.transaction.TransactionManager;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Explicit candidate composition boundary. It is not a CDI bean or route. Its caller must supply trusted admission,
 * target approval, SDK owner context, pre-entry audit check and a synchronous, bounded co-located central audit sink.
 * This does not establish production configuration or protect a target call already in flight.
 */
@JsonSerialize(using = JdbcResourceExecutionGate.TransientControlSerializer.class)
public final class GovernedExecutionRoot implements AutoCloseable {
    private final JdbcResourceExecutionGate gate;
    private final JdbcResearchOutcomes researchOutcomes;

    private GovernedExecutionRoot(DataSource data, TransactionManager transactions, int maxInFlight,
                                  JdbcResourceExecutionGate.IoBounds bounds, IdempotentAuditSink sink,
                                  String checkpointTargetFingerprint) {
        gate = new JdbcResourceExecutionGate(Objects.requireNonNull(data), Objects.requireNonNull(transactions),
                maxInFlight, Objects.requireNonNull(bounds), new ResourceEntryAuditSource(), Objects.requireNonNull(sink));
        researchOutcomes = checkpointTargetFingerprint == null ? null
                : new JdbcResearchOutcomes(data, transactions, checkpointTargetFingerprint);
    }

    /** The shared root owns one bounded gate; no unaudited construction is exposed through this factory. */
    public static GovernedExecutionRoot audited(DataSource data, TransactionManager transactions, int maxInFlight,
                                                 JdbcResourceExecutionGate.IoBounds bounds, IdempotentAuditSink sink) {
        return new GovernedExecutionRoot(data, transactions, maxInFlight, bounds, sink, null);
    }

    /** Pins the research checkpoint target to the same configured data source as the entry gate. */
    public static GovernedExecutionRoot auditedResearch(DataSource data, TransactionManager transactions, int maxInFlight,
            JdbcResourceExecutionGate.IoBounds bounds, IdempotentAuditSink sink, String checkpointTargetFingerprint) {
        return new GovernedExecutionRoot(data, transactions, maxInFlight, bounds, sink,
                Objects.requireNonNull(checkpointTargetFingerprint));
    }

    /**
     * Executes synchronously with the exact identity, policy, task and real owner delegates. The caller owns those
     * single-call delegates and must retire them only after this method returns. The callback must not detach work.
     * The pre-entry audit check is distinct from the entry receipt, which the gate retains and delivers after commit.
     */
    public <T> JdbcResourceExecutionGate.Result<T> execute(ResourceCallBinding binding, ResourceExecutionEntry entry,
            UUID executor, LiveIdentityResourceFence identity, LiveResearchPolicyFence policy,
            ResourceTaskExecutionFence task, WorkflowOwnerResourceFence owner, ResourceExecutionFence preEntryAudit,
            Function<JdbcResourceExecutionGate.Running, T> callback) {
        var composite = composite(identity, policy, task, owner, preEntryAudit);
        var credential = pair(binding, identity, policy, task, composite);
        return gate.openGoverned(binding, entry, executor, credential).executeGoverned(callback);
    }

    /**
     * Transfers exclusive ownership of the four single-call contexts to a gate-owned worker. Cancellation invalidates
     * the attempt but does not close the contexts or release the worker slot until synchronous work really returns.
     * Submission failure closes them immediately. The callback must not detach work or return an async value.
     */
    public <T> Future<JdbcResourceExecutionGate.Result<T>> executeAsync(ResourceCallBinding binding,
            ResourceExecutionEntry entry, UUID executor, LiveIdentityResourceFence identity,
            LiveResearchPolicyFence policy, ResourceTaskExecutionFence task, WorkflowOwnerResourceFence owner,
            ResourceExecutionFence preEntryAudit, Function<JdbcResourceExecutionGate.Running, T> callback) {
        var retired = new AtomicBoolean();
        Runnable retire = () -> {
            if (retired.compareAndSet(false, true)) closeContexts(owner, task, policy, identity);
        };
        try {
            Objects.requireNonNull(identity); Objects.requireNonNull(policy); Objects.requireNonNull(task); Objects.requireNonNull(owner);
            var composite = composite(identity, policy, task, owner, preEntryAudit);
            var credential = pair(binding, identity, policy, task, composite);
            return gate.executeGovernedAsync(binding, entry, executor, credential, callback, retire);
        } catch (RuntimeException | Error failure) {
            try { retire.run(); }
            catch (RuntimeException | Error cleanup) {
                if (cleanup instanceof Error fatal && !(failure instanceof Error)) {
                    fatal.addSuppressed(failure); throw fatal;
                }
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Runs the graph and retains terminal checkpoint attribution; private output needs a separate verified release. */
    public Future<JdbcResourceExecutionGate.Result<ResourceExecutionOutcome>> executeResearch(ResourceCallBinding binding,
            ResourceExecutionEntry entry, UUID executor, LiveIdentityResourceFence identity,
            LiveResearchPolicyFence policy, ResourceTaskExecutionFence task, WorkflowOwnerResourceFence owner,
            ResourceExecutionFence preEntryAudit, ResearchAgent agent, AllocatedResourceTask allocation, String topic) {
        Objects.requireNonNull(agent); Objects.requireNonNull(allocation); Objects.requireNonNull(topic);
        if (researchOutcomes == null) throw new IllegalStateException("research checkpoint target is not configured");
        return executeAsync(binding, entry, executor, identity, policy, task, owner, preEntryAudit,
                running -> {
                    agent.runGoverned(running, allocation, topic);
                    running.requireCurrent(entry, executor);
                    var outcome = researchOutcomes.associate(entry);
                    running.requireCurrent(entry, executor);
                    return outcome;
                });
    }

    private static CompositeExecutionFence composite(LiveIdentityResourceFence identity, LiveResearchPolicyFence policy,
            ResourceTaskExecutionFence task, WorkflowOwnerResourceFence owner, ResourceExecutionFence preEntryAudit) {
        return CompositeExecutionFence.builder()
                .with(CompositeExecutionFence.Check.IDENTITY, identity)
                .with(CompositeExecutionFence.Check.POLICY, policy)
                .with(CompositeExecutionFence.Check.TASK_AND_TARGET, task)
                .with(CompositeExecutionFence.Check.OWNER_AND_LEASE, owner)
                .with(CompositeExecutionFence.Check.AUDIT, preEntryAudit).build();
    }

    private static void closeContexts(WorkflowOwnerResourceFence owner, ResourceTaskExecutionFence task,
                                      LiveResearchPolicyFence policy, LiveIdentityResourceFence identity) {
        Throwable failure = null;
        for (Runnable close : new Runnable[]{() -> { if (owner != null) owner.close(); },
                () -> { if (task != null) task.close(); }, () -> { if (policy != null) policy.close(); },
                () -> { if (identity != null) identity.close(); }}) {
            try { close.run(); }
            catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught;
                else if (caught instanceof Error && !(failure instanceof Error)) {
                    caught.addSuppressed(failure); failure = caught;
                }
                else if (caught != failure) failure.addSuppressed(caught);
            }
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    @Override public void close() { gate.close(); }

    public static JdbcResourceExecutionGate.CredentialFence pair(ResourceCallBinding binding,
            LiveIdentityResourceFence identity, LiveResearchPolicyFence policy, ResourceTaskExecutionFence task,
            CompositeExecutionFence composite) {
        Objects.requireNonNull(identity, "identity").requirePairing(binding, composite, policy, task);
        return JdbcResourceExecutionGate.CredentialFence.pair(binding, composite);
    }
}
