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
 *   <li>A timeout, transport error or provider failure returns {@link ModelResult.Failed}. A model call changes no
 *       external system, so a retry is safe, but the cost of a partial call may still have been spent and must be
 *       recorded if the provider reports it.</li>
 *   <li>The gateway never returns text after an error and never switches to another model silently.</li>
 * </ul>
 */
public interface ModelGateway {

    /** Calls the model once and returns exactly one of the sealed outcomes. */
    ModelResult complete(ModelCall call);
}
