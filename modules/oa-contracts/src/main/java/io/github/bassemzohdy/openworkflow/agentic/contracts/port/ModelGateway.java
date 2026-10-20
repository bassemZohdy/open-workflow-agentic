package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

/**
 * The only path from an agent to a model (ADR 0012, M14, M34). An implementation checks the task's contract and the
 * policy decision point, enforces the remaining cost budget and the time limit, and records the call and its cost.
 * Callers depend on this port, never on a provider SDK.
 *
 * <p><b>Failure semantics: fail closed.</b>
 * <ul>
 *   <li>A model not allowed for the task, a DENY or ERROR from the policy decision point, or an exhausted budget
 *       returns {@link ModelResult.Rejected}. No call is made.</li>
 *   <li>A timeout, transport error or provider failure returns {@link ModelResult.Failed}. A partial/unknown call
 *       may already be billed. Retain its original durable allowance; an error is not proof of nonapplication or
 *       permission to retry under a new key/model/budget. Any explicitly approved retry needs its own bounded
 *       provider/idempotency/usage recovery protocol. Never infer safe/free repetition from a missing answer.</li>
 *   <li>The gateway never returns text after an error and never switches to another model silently.</li>
 * </ul>
 */
public interface ModelGateway {

    /** Calls the model once and returns exactly one of the sealed outcomes. */
    ModelResult complete(ModelCall call);
}
