package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.FailClosedPolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.SafeRecovery.RecoveryOperation;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The policy enforcement point (M12, ADR 0021): the one way the runtime asks whether a boundary may proceed.
 *
 * <p>Four boundaries have typed entry points: a tool call, a model call, a human-task action, and a publication or
 * activation. Each builds the {@link AuthorizationRequest} from the approved {@link AgentTaskContract} (or the
 * human task or artifact) and from the call, and asks the policy decision point. Operator operations (cancel, pause,
 * drain) are the fifth entry point. Each entry point returns a {@link Decision}, and {@link #guard} runs a call only
 * when that decision is ALLOW and throws {@link PolicyDenied} otherwise.
 *
 * <p><b>Decision path.</b>
 * <ol>
 *   <li>The policy engine is called through {@link FailClosedPolicyDecisionPoint}, so a throw, a null, a timeout or
 *       an interrupt becomes ERROR.</li>
 *   <li>The decision is handed to the {@link AuditSink}. If that fails, the decision becomes ERROR, so nothing is
 *       allowed without a record.</li>
 *   <li>If the main decision is ERROR and the request is an operator operation, the built-in
 *       {@link SafeRecovery} rule decides instead. It allows only what it names, and the audit sink records that
 *       decision too.</li>
 * </ol>
 *
 * <p>The instance is thread-safe if its engine and audit sink are.
 */
public final class Pep {

    /** Decision timeout used when none is given. Cedar answers in well under a millisecond. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);
    /** Name of the component in decisions this class produces itself. */
    public static final String DECIDED_BY = "pep";

    private final PolicyDecisionPoint decisions;
    private final AuditSink audit;
    private final Clock clock;

    public Pep(PolicyDecisionPoint engine, AuditSink audit, Duration timeout, Clock clock) {
        Objects.requireNonNull(engine, "engine");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.decisions = new FailClosedPolicyDecisionPoint(engine, timeout, clock);
    }

    public Pep(PolicyDecisionPoint engine, AuditSink audit) {
        this(engine, audit, DEFAULT_TIMEOUT, Clock.systemUTC());
    }

    /**
     * Runs {@code call} only if {@code decision} is ALLOW. Any other outcome throws {@link PolicyDenied} and the call
     * is never made.
     */
    public static <T> T guard(Decision decision, Supplier<T> call) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(call, "call");
        if (!decision.allows()) {
            throw new PolicyDenied(decision);
        }
        return call.get();
    }

    /** Boundary 1: an agent calls a tool. {@code permittedByContract} is taken from the task's allow-list. */
    public Decision toolCall(Subject agent, AgentTaskContract task, String tool, long budgetRemainingMicros) {
        Objects.requireNonNull(task, "task");
        Checks.requireText("tool", tool, 128);
        return decide(agent, ActionKind.TOOL_CALL, "tool:" + tool, task.id(),
                contextOf(task, budgetRemainingMicros, task.permitsTool(tool)));
    }

    /**
     * Boundary 2: an agent calls a model. The contract has no model allow-list, so {@code permittedByContract} is
     * unknown and reaches the policy as null.
     */
    public Decision modelCall(Subject agent, AgentTaskContract task, String modelId, long budgetRemainingMicros) {
        Objects.requireNonNull(task, "task");
        Checks.requireText("modelId", modelId, 128);
        return decide(agent, ActionKind.MODEL_CALL, "model:" + modelId, task.id(),
                contextOf(task, budgetRemainingMicros, null));
    }

    /**
     * Boundary 3: a person acts on a human task (approve, redirect, take over). {@code action} is a verb such as
     * {@code approve}; the task's agent task, when there is one, is the request's task.
     */
    public Decision humanTaskAction(Subject actor, Set<String> actorScopes, HumanTask task, String action) {
        Objects.requireNonNull(task, "task");
        Checks.requireMatching("action", action, AgentTaskContract.TOKEN);
        return decide(actor, ActionKind.HUMAN_TASK_ACTION, "human-task:" + task.id().value() + ":" + action,
                task.agentTaskId(), new PolicyContext(actorScopes, null, null, null, null, null));
    }

    /** Boundary 4: a versioned artifact is published or activated. The digest is part of the resource. */
    public Decision publication(Subject publisher, Set<String> publisherScopes, ArtifactRef artifact) {
        Objects.requireNonNull(artifact, "artifact");
        String resource = "artifact:" + artifact.kind().name() + "/" + artifact.name() + "@" + artifact.version()
                + "#" + artifact.sha256();
        return decide(publisher, ActionKind.PUBLICATION, resource, null,
                new PolicyContext(publisherScopes, null, null, null, null, null));
    }

    /** An operator runs a safe-recovery operation. {@code taskId} is null only for {@link RecoveryOperation#DRAIN}. */
    public Decision operation(RecoveryOperation operation, Subject operator, Set<String> operatorScopes, Ulid taskId) {
        Objects.requireNonNull(operation, "operation");
        return decide(operator, ActionKind.OPERATION, operation.resource(), taskId,
                new PolicyContext(operatorScopes, null, null, null, null, null));
    }

    /**
     * Operator operations that are not safe-recovery operations (WP-105d): inspect, retry and recover. They take the
     * same path as {@link #operation}, but {@link SafeRecovery} does not name them, so when the main policy is
     * unavailable they are refused (ERROR) and nothing changes. {@link RecoveryOperation} is deliberately not extended.
     */
    public enum InstanceOperation {
        INSPECT("operation:inspect"),
        RETRY("operation:retry"),
        RECOVER("operation:recover");

        private final String resource;

        InstanceOperation(String resource) {
            this.resource = resource;
        }

        /** The resource string a request for this operation carries. */
        public String resource() {
            return resource;
        }
    }

    /**
     * An operator acts on one registry instance. The request's task id carries the instance id, because the request
     * shape has no instance field (docs/lifecycle.md, section 11, and docs/policy.md).
     */
    public Decision instanceOperation(InstanceOperation operation, Subject operator, Set<String> operatorScopes,
                                      Ulid instanceId) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(instanceId, "instanceId");
        return decide(operator, ActionKind.OPERATION, operation.resource(), instanceId,
                new PolicyContext(operatorScopes, null, null, null, null, null));
    }

    /** Decides any request through the full path: engine, audit, and the recovery rule when the engine is unavailable. */
    public Decision decide(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        Decision primary = record(request, decisions.decide(request), false);
        if (primary.outcome() != DecisionOutcome.ERROR || request.action() != ActionKind.OPERATION) {
            return primary;
        }
        Decision recovery = record(request, SafeRecovery.evaluate(request, clock.instant()), true);
        return recovery.allows() ? recovery : primary;
    }

    private Decision decide(Subject subject, ActionKind kind, String resource, Ulid taskId, PolicyContext context) {
        return decide(new AuthorizationRequest(subject, kind, resource, taskId, context));
    }

    private static PolicyContext contextOf(AgentTaskContract task, long budgetRemainingMicros, Boolean permitted) {
        return new PolicyContext(task.permittedDataScopes(), task.riskTier(), budgetRemainingMicros,
                task.requestingSubject(), task.delegationGrantId(), permitted);
    }

    /** Writes the decision to the audit sink. A failed write turns the decision into a refusal. */
    private Decision record(AuthorizationRequest request, Decision decision, boolean recoveryPath) {
        try {
            audit.record(new PolicyDecisionRecord(request, decision, recoveryPath));
            return decision;
        } catch (RuntimeException e) {
            return new Decision(DECIDED_BY, DecisionOutcome.ERROR,
                    "decision not recorded (" + e.getClass().getSimpleName() + "); refused", PolicyRef.FAIL_CLOSED,
                    clock.instant());
        }
    }
}
