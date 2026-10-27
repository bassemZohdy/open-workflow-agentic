package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Retained full-ceiling allowance, NOT measured expenditure or a provider capability. No refund/retry/usage is
 * inferred from exceptions, missing replies or historical readback. Remaining fields are the original committed
 * snapshot AFTER this reservation, not a live balance; protectedCallBudgetMicros belongs to this call only.
 */
public record ModelCallReservation(ResourceModelCall call, AllocatedResourceTask task, ModelBudgetAccount account,
                                    long heldMicros, long allocationRemainingAfterMicros, long accountRemainingAfterMicros,
                                    int ordinal, int callsRemaining, Instant reservedAt, Instant callDeadline) {
    public ModelCallReservation {
        Objects.requireNonNull(call); Objects.requireNonNull(task); Objects.requireNonNull(account);
        var allocation = task.allocation(); var scope = allocation.scope();
        if (!call.entry().scope().equals(scope) || !scope.tenant().equals(account.tenant())
                || !scope.instanceId().equals(account.instanceId()) || !scope.owner().equals(account.owner())
                || !ModelBudgetAccount.keyFor(allocation).equals(account.key())
                || account.anchorKind() != ModelBudgetAccount.anchorKind(allocation)
                || !account.anchorId().equals(ModelBudgetAccount.anchorId(allocation))
                || call.maxOutputTokens() > allocation.modelProfile().maxOutputTokens())
            throw new IllegalArgumentException("reservation attribution differs");
        if (heldMicros != allocation.modelProfile().perCallCeilingMicros()
                || heldMicros > account.maxCostMicros() || allocationRemainingAfterMicros < 0
                || allocationRemainingAfterMicros > allocation.contract().limits().maxCostMicros() - heldMicros
                || accountRemainingAfterMicros < 0 || accountRemainingAfterMicros > account.maxCostMicros() - heldMicros
                || ordinal < 1 || ordinal > allocation.modelProfile().maxModelCalls()
                || callsRemaining != allocation.modelProfile().maxModelCalls() - ordinal)
            throw new IllegalArgumentException("reservation amounts/counts differ");
        if (allocation.parentTaskId() == null
                && !AgentTaskContractFingerprint.of(allocation.contract()).equals(account.sourceContractFingerprint()))
            throw new IllegalArgumentException("allocation budget source differs");
        ModelBudgetAccount.timestamp(reservedAt); ModelBudgetAccount.timestamp(callDeadline);
        if (reservedAt.isBefore(task.createdAt()) || reservedAt.isBefore(account.createdAt())
                || !callDeadline.isAfter(reservedAt) || callDeadline.isAfter(task.deadline())
                || callDeadline.isAfter(account.deadline()) || callDeadline.isAfter(reservedAt.plus(
                    allocation.modelProfile().maxCallTime().truncatedTo(ChronoUnit.MICROS))))
            throw new IllegalArgumentException("reservation original deadline differs");
    }
    /** Guaranteed protected allowance for this call, not free total balance or measured usage. */
    public long protectedCallBudgetMicros() { return heldMicros; }
    public String key() { return call.key(); }
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.model.reservation.v1")
                .text(call.key()).text(call.fingerprint()).text(task.allocation().fingerprint())
                .number(task.createdAt().getEpochSecond()).number(task.createdAt().getNano())
                .number(task.deadline().getEpochSecond()).number(task.deadline().getNano())
                .text(account.key()).text(account.fingerprint()).number(heldMicros)
                .number(allocationRemainingAfterMicros).number(accountRemainingAfterMicros).number(ordinal).number(callsRemaining)
                .number(reservedAt.getEpochSecond()).number(reservedAt.getNano())
                .number(callDeadline.getEpochSecond()).number(callDeadline.getNano()).toBytes());
    }
}
