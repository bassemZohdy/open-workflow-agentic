package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The built-in safe-recovery rule (M12, M27): the only decision that does not come from the policy snapshot. It lets an
 * authorised operator stop work, and nothing else, when the main policy cannot decide.
 *
 * <p><b>When it is consulted.</b> {@link Pep} consults it only for an {@link ActionKind#OPERATION} request whose main
 * decision is ERROR (no snapshot, a failed evaluation, an engine failure or a timeout). A main DENY is final: a
 * deliberate refusal in an approved policy is never overridden by this rule.
 *
 * <p><b>What it allows</b>, all of these at once:
 * <ul>
 *   <li>the action is an {@link ActionKind#OPERATION} and its resource names one of {@link RecoveryOperation};</li>
 *   <li>the subject is a {@link SubjectKind#USER}: an agent, workflow or service never operates the runtime;</li>
 *   <li>the context carries the {@link #OPERATOR_SCOPE} scope. The rule trusts the scopes it is handed, so the caller
 *       must take them from the operator's verified token;</li>
 *   <li>{@code CANCEL} and {@code PAUSE} name one agent task, and {@code DRAIN} names no task, because it applies to
 *       the runtime.</li>
 * </ul>
 * It reads no snapshot, calls no engine and needs no database, so it works when all of those are unavailable. It is
 * deliberately small and is not a second policy language.
 */
public final class SafeRecovery {

    /** The operations that remain possible in degraded mode. Each one stops or holds work; none starts or widens it. */
    public enum RecoveryOperation {
        /** Stops one agent task. Its effects already committed stay recorded. */
        CANCEL("operation:cancel", true),
        /** Holds one agent task at its next checkpoint. Resuming it needs fresh authorization. */
        PAUSE("operation:pause", true),
        /** Stops accepting new work across the runtime and lets in-flight work finish. */
        DRAIN("operation:drain", false);

        private final String resource;
        private final boolean needsTask;

        RecoveryOperation(String resource, boolean needsTask) {
            this.resource = resource;
            this.needsTask = needsTask;
        }

        /** The resource string a request for this operation carries. */
        public String resource() {
            return resource;
        }

        boolean needsTask() {
            return needsTask;
        }

        static Optional<RecoveryOperation> fromResource(String resource) {
            return Arrays.stream(values()).filter(op -> op.resource.equals(resource)).findFirst();
        }
    }

    /** Scope an operator must hold for the recovery rule. Fixed in code, so no snapshot can change it. */
    public static final String OPERATOR_SCOPE = "oa.recover";
    /** Decided-by name of decisions from this rule. */
    public static final String DECIDED_BY = "safe-recovery";
    /** Policy reference of this rule. It is built in and not part of any snapshot. */
    public static final PolicyRef POLICY = new PolicyRef("oa.safe-recovery", "1");

    private SafeRecovery() {
    }

    /** Decides one request under the recovery rule. Returns ALLOW only for an operator's recovery operation. */
    public static Decision evaluate(AuthorizationRequest request, Instant now) {
        if (request.action() != ActionKind.OPERATION) {
            return deny(now, "not a recovery operation");
        }
        Optional<RecoveryOperation> operation = RecoveryOperation.fromResource(request.resource());
        if (operation.isEmpty()) {
            return deny(now, "unknown recovery operation");
        }
        if (request.subject().kind() != SubjectKind.USER) {
            return deny(now, "only a person may run a recovery operation");
        }
        if (!request.context().scopes().contains(OPERATOR_SCOPE)) {
            return deny(now, "operator does not hold the recovery scope");
        }
        boolean hasTask = request.taskId() != null;
        if (operation.get().needsTask() && !hasTask) {
            return deny(now, operation.get().name().toLowerCase(Locale.ROOT) + " needs an agent task");
        }
        if (!operation.get().needsTask() && hasTask) {
            return deny(now, "drain applies to the runtime and names no task");
        }
        return new Decision(DECIDED_BY, DecisionOutcome.ALLOW,
                "main policy unavailable; recovery operation " + operation.get().name().toLowerCase(Locale.ROOT)
                        + " allowed for an authorised operator",
                POLICY, now);
    }

    private static Decision deny(Instant now, String reason) {
        return new Decision(DECIDED_BY, DecisionOutcome.DENY, reason, POLICY, now);
    }
}
