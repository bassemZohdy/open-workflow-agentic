package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.Objects;

/**
 * Deterministic reference-only envelope for a proposed human-decision wakeup. This is data, not proof that the
 * decision is committed, audited, authorized, or associated with the supplied wait. An eventual producer must load
 * and verify those retained facts; a consumer must also evaluate the pinned SDK predicate and current ownership.
 * Nothing here persists, matches, dispatches or releases a workflow.
 */
public final class HumanDecisionWake {
    public static final String SOURCE = "urn:openworkflow:human-decision";
    public static final String TYPE = "io.openworkflow.human.decision.v1";
    private HumanDecisionWake() { }

    /** A task-specific route, so different human tasks do not share the same correlation route by default. */
    public static String route(Ulid taskId) { return "human:" + Objects.requireNonNull(taskId, "taskId").value(); }

    /** The body contains references only; decision content, actor and outcome stay in governed storage. */
    public static InboxMessage message(HumanDecision decision, InboxWait wait) {
        Objects.requireNonNull(decision, "decision"); Objects.requireNonNull(wait, "wait");
        if (!decision.tenant().equals(wait.tenant()) || !route(decision.taskId()).equals(wait.route())
                || !TYPE.equals(wait.eventType())) throw new IllegalArgumentException("human decision and wait do not match");
        // ULIDs and the lowercase hex digest have fixed, JSON-safe alphabets; no decision text is interpolated.
        String body = "{\"decisionId\":\"" + decision.id().value() + "\",\"taskId\":\"" + decision.taskId().value()
                + "\",\"waitDigest\":\"" + wait.digest() + "\"}";
        return new InboxMessage(wait.tenant(), SOURCE, decision.id().value(), TYPE, wait.instanceId(), wait.route(), body);
    }

    /** Exact identity/content check in addition to the SDK predicate; equality alone is never delivery permission. */
    public static boolean matches(HumanDecision decision, InboxWait wait, InboxMessage candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try { return message(decision, wait).equals(candidate); }
        catch (IllegalArgumentException mismatch) { return false; }
    }
}
