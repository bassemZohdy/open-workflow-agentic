package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Original immutable source cap for MODEL reservations. Other cost categories must converge before full budget closure. */
public record ModelBudgetAccount(String key, String tenant, Ulid instanceId, Subject owner, AnchorKind anchorKind,
                                  String anchorId, String sourceContractFingerprint, long maxCostMicros,
                                  Instant createdAt, Instant deadline) {
    public enum AnchorKind { TASK, ALLOCATION }
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    public ModelBudgetAccount {
        tenant = Checks.requireText("tenant", tenant, 256); CanonicalEncoder.strictUtf8(tenant);
        Objects.requireNonNull(instanceId); Objects.requireNonNull(owner); CanonicalEncoder.strictUtf8(owner.id());
        Objects.requireNonNull(anchorKind); Objects.requireNonNull(anchorId);
        if (anchorKind == AnchorKind.TASK) Ulid.parse(anchorId);
        else Checks.requireMatching("allocation anchor", anchorId, Pattern.compile("ra1-[0-9a-f]{64}"));
        if (!keyFor(tenant, instanceId, owner, anchorKind, anchorId).equals(key))
            throw new IllegalArgumentException("budget account identity differs");
        sourceContractFingerprint = Checks.requireMatching("source contract", sourceContractFingerprint, HASH);
        Checks.requireInRange("maxCostMicros", maxCostMicros, 1L, ExecutionLimits.MAX_COST_MICROS);
        timestamp(createdAt); timestamp(deadline);
        if (!deadline.isAfter(createdAt) || deadline.isAfter(createdAt.plus(ExecutionLimits.MAX_WALL_CLOCK)))
            throw new IllegalArgumentException("budget account original lifetime differs");
    }
    public static String keyFor(String tenant, Ulid instance, Subject owner, AnchorKind kind, String id) {
        return "mb1-" + CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.model.budget-account.v1")
                .text(tenant).text(instance.value()).text(owner.kind().name()).text(owner.id()).text(kind.name()).text(id).toBytes());
    }
    public static String keyFor(ResourceTaskAllocation allocation) {
        var s = allocation.scope();
        return keyFor(s.tenant(), s.instanceId(), s.owner(), anchorKind(allocation), anchorId(allocation));
    }
    public static AnchorKind anchorKind(ResourceTaskAllocation allocation) {
        return allocation.parentTaskId() == null ? AnchorKind.ALLOCATION : AnchorKind.TASK;
    }
    public static String anchorId(ResourceTaskAllocation allocation) {
        return allocation.parentTaskId() == null ? allocation.key() : allocation.parentTaskId().value();
    }
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.model.budget-account-contract.v1")
                .text(key).text(sourceContractFingerprint).number(maxCostMicros)
                .number(createdAt.getEpochSecond()).number(createdAt.getNano())
                .number(deadline.getEpochSecond()).number(deadline.getNano()).toBytes());
    }
    static void timestamp(Instant value) {
        Objects.requireNonNull(value, "timestamp");
        if (value.getNano() % 1000 != 0) throw new IllegalArgumentException("exact PostgreSQL microseconds required");
    }
}
