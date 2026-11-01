package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ArtifactRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanActionFacts;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTask;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanTaskStatus;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
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
    /** Maximum concurrent policy evaluations admitted by the PEP; excess calls fail closed immediately. */
    public static final int POLICY_DECISION_MAX_IN_FLIGHT = 64;

    private final PolicyDecisionPoint decisions;
    private final AuditSink audit;
    private final Clock clock;

    public Pep(PolicyDecisionPoint engine, AuditSink audit, Duration timeout, Clock clock) {
        this(engine, audit, timeout, clock, POLICY_DECISION_MAX_IN_FLIGHT);
    }

    /** Creates the enforcement point with an explicit policy-decision bulkhead bound. */
    public Pep(PolicyDecisionPoint engine, AuditSink audit, Duration timeout, Clock clock, int maxInFlight) {
        Objects.requireNonNull(engine, "engine");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.decisions = new FailClosedPolicyDecisionPoint(engine, timeout, clock, maxInFlight);
    }

    public Pep(PolicyDecisionPoint engine, AuditSink audit) {
        this(engine, audit, DEFAULT_TIMEOUT, Clock.systemUTC(), POLICY_DECISION_MAX_IN_FLIGHT);
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

    /**
     * Boundary 3, closed vocabulary (WP-106). Deterministic rules run before the policy engine and a failure is a
     * recorded DENY with zero engine calls: only a {@link SubjectKind#USER} may act; the task must still be open or
     * in progress; the actor must hold {@link HumanAction#requiredScope()}; and a decision is taken only by the
     * task's current assignee. Rules that need facts the task does not carry (tenant, initiator, risk) are not
     * guessed here: they wait for the shared {@code HumanActionFacts} contract and the engine decides the rest.
     */
    public Decision humanTaskAction(Subject actor, Set<String> actorScopes, HumanTask task, HumanAction action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(actorScopes, "actorScopes");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(action, "action");
        AuthorizationRequest request = new AuthorizationRequest(actor, ActionKind.HUMAN_TASK_ACTION,
                "human-task:" + task.id().value() + ":" + action.verb(), task.agentTaskId(),
                new PolicyContext(actorScopes, null, null, null, null, null));
        String refusal = humanActionRefusal(actor, actorScopes, task, action);
        if (refusal != null) {
            return record(request, new Decision(DECIDED_BY, DecisionOutcome.DENY, refusal,
                    PolicyRef.FAIL_CLOSED, clock.instant()), false);
        }
        return decide(request);
    }

    private static String humanActionRefusal(Subject actor, Set<String> scopes, HumanTask task, HumanAction action) {
        return humanActionRefusal(actor, scopes, task.status(), task.assignee(), action);
    }

    private static String humanActionRefusal(Subject actor, Set<String> scopes, HumanTaskStatus status,
                                             Subject assignee, HumanAction action) {
        if (actor.kind() != SubjectKind.USER) {
            return "only a person may act on a human task";
        }
        if (status != HumanTaskStatus.OPEN && status != HumanTaskStatus.IN_PROGRESS) {
            return "the task is " + status + " and no longer accepts actions";
        }
        if (!scopes.contains(action.requiredScope())) {
            return "missing scope " + action.requiredScope();
        }
        if (action == HumanAction.DECIDE && !actor.equals(assignee)) {
            return "only the task's assignee may decide it";
        }
        return null;
    }

    /**
     * Boundary 3 with the retained facts of the task (WP-106). Everything {@link #humanTaskAction(Subject, Set, HumanTask,
     * HumanAction)} refuses is refused here too, and in addition, before the policy engine and with zero engine calls:
     * the actor's tenant must be the task's tenant, a task past its due time accepts no action, and when the approved
     * risk policy attached {@link HumanActionFacts.SeparationObligation#INITIATOR_MUST_NOT_ACT} the initiator of the work
     * may neither decide nor route the task. No obligation is invented: a task with {@code NONE} is not restricted
     * by the initiator rule, and no rule here depends on the risk tier alone.
     *
     * <p>The facts are what the decision was made from. The caller must still check, after this call and before the
     * durable mutation, that the task version, status, assignee and deadline are unchanged; an ALLOW is never reusable
     * against changed facts.
     */
    public Decision humanTaskAction(Subject actor, String actorTenant, Set<String> actorScopes, HumanActionFacts facts,
                                    HumanAction action) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(actorScopes, "actorScopes");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(action, "action");
        AuthorizationRequest request = new AuthorizationRequest(actor, ActionKind.HUMAN_TASK_ACTION,
                "human-task:" + facts.taskId().value() + ":" + action.verb(), facts.agentTaskId(),
                new PolicyContext(actorScopes, facts.riskTier(), null, null, null, null));
        String refusal = humanActionRefusal(actor, actorScopes, facts.status(), facts.assignee(), action);
        if (refusal == null && (actorTenant == null || !actorTenant.equals(facts.tenant()))) {
            refusal = "the actor is not in the task's tenant";
        }
        if (refusal == null && facts.dueAt() != null && !clock.instant().isBefore(facts.dueAt())) {
            refusal = "the task is past its due time";
        }
        if (refusal == null && facts.separation() == HumanActionFacts.SeparationObligation.INITIATOR_MUST_NOT_ACT
                && action != HumanAction.CANCEL && actor.equals(facts.initiator())) {
            refusal = "the initiator of the work may not act on this task";
        }
        if (refusal != null) {
            return record(request, new Decision(DECIDED_BY, DecisionOutcome.DENY, refusal,
                    PolicyRef.FAIL_CLOSED, clock.instant()), false);
        }
        return decide(request);
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
        RECOVER("operation:recover"),
        /** A manual decision on an unknown governed effect (WP-108); needs the verified oa.recover scope. */
        RESOLVE_EFFECT("operation:resolve-effect");

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

    /** Resource of a direct invocation of the research agent over HTTP (WP-103). */
    public static final String AGENT_RESEARCH_RUN = "agent:research:run";

    /**
     * A verified caller runs the research agent directly ({@code POST /agents/research}). The request is an
     * {@code OPERATION} on {@link #AGENT_RESEARCH_RUN}; {@link SafeRecovery} does not name it, so when the policy
     * engine is unavailable the call is refused (ERROR) and the agent does not run.
     */
    public Decision agentInvocation(Subject caller, Set<String> callerScopes) {
        return decide(caller, ActionKind.OPERATION, AGENT_RESEARCH_RUN, null,
                new PolicyContext(callerScopes, null, null, null, null, null));
    }

    /**
     * A verified caller reads or verifies the audit trail (WP-103). A {@code DATA_ACCESS} decision on the action name
     * ({@code audit.events.read} or {@code audit.chains.verify}); the decision is audited and bounded like every PEP
     * decision, and an unavailable engine is ERROR (safe recovery covers {@code OPERATION} only).
     */
    public Decision auditAccess(Subject caller, Set<String> callerScopes, String action) {
        Objects.requireNonNull(action, "action");
        return decide(caller, ActionKind.DATA_ACCESS, action, null,
                new PolicyContext(callerScopes, null, null, null, null, null));
    }

    /**
     * The instance API (WP-103): the HTTP routes that start, read and re-authorize a registry instance. Each has its own
     * resource, distinct from the operator operations on the same instance, so a rule for one cannot allow another.
     * None is named by {@link SafeRecovery}, so when the policy engine or the audit sink is unavailable the decision is
     * ERROR and nothing is started, read or renewed.
     */
    public enum InstanceApi {
        START("instance:start"),
        GET("instance:get"),
        REAUTHORIZE("instance:reauthorize");

        private final String resource;

        InstanceApi(String resource) {
            this.resource = resource;
        }

        /** The resource string a request for this route carries. */
        public String resource() {
            return resource;
        }
    }

    /**
     * A verified caller uses the instance API. {@code instanceId} is the instance in the path (the request's task id);
     * it is null for a start, and for a path segment that is not an instance id at all.
     */
    public Decision instanceApi(InstanceApi route, Subject caller, Set<String> callerScopes, Ulid instanceId) {
        Objects.requireNonNull(route, "route");
        return decide(caller, ActionKind.OPERATION, route.resource(), instanceId,
                new PolicyContext(callerScopes, null, null, null, null, null));
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
            audit.record(PolicyDecisionAuditEvent.from(new PolicyDecisionRecord(request, decision, recoveryPath)));
            return decision;
        } catch (RuntimeException e) {
            return new Decision(DECIDED_BY, DecisionOutcome.ERROR,
                    "decision not recorded (" + e.getClass().getSimpleName() + "); refused", PolicyRef.FAIL_CLOSED,
                    clock.instant());
        }
    }
}
