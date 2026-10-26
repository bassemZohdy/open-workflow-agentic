package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import java.util.Objects;
import java.util.TreeSet;

/** Versioned full contract data digest. It is neither approval nor current permission. */
public final class AgentTaskContractFingerprint {
    private AgentTaskContractFingerprint() { }

    public static String of(AgentTaskContract task) {
        Objects.requireNonNull(task, "task");
        var encoded = new CanonicalEncoder("oa.agent.task-contract.v1").text(task.id().value()).text(task.goal());
        encoded.number(task.permittedTools().size());
        new TreeSet<>(task.permittedTools()).forEach(encoded::text);
        encoded.number(task.permittedDataScopes().size());
        new TreeSet<>(task.permittedDataScopes()).forEach(encoded::text);
        encoded.text(task.riskTier().name()).number(task.limits().maxIterations())
                .number(task.limits().maxWallClock().getSeconds()).number(task.limits().maxWallClock().getNano())
                .number(task.limits().maxCostMicros()).text(task.requestingSubject().kind().name())
                .text(task.requestingSubject().id()).text(task.delegationGrantId().value());
        return CanonicalEncoder.sha256Hex(encoded.toBytes());
    }
}
