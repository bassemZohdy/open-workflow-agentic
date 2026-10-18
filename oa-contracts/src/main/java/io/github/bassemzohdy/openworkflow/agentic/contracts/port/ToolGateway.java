package io.github.bassemzohdy.openworkflow.agentic.contracts.port;

/**
 * The only path from an agent to a tool (ADR 0022, M02, M12, M14). An implementation checks the task's contract
 * ({@code AgentTaskContract.permitsTool}), asks the policy decision point, enforces the limits, applies timeouts and
 * records the call in the audit log.
 *
 * <p><b>Failure semantics: fail closed.</b>
 * <ul>
 *   <li>A tool that the contract does not list, a DENY, an ERROR from the policy decision point, or a missing task
 *       returns {@link ToolResult.Rejected}. The tool is not called.</li>
 *   <li>A timeout or crash of the tool returns {@link ToolResult.Failed}. For a tool that changes an external system,
 *       {@code effectMayHaveHappened} is true: the caller must treat the effect as unresolved and reconcile it through
 *       the {@link EffectLedger}, not retry it under a new key.</li>
 *   <li>The gateway never returns a completed output after an error, and never falls back to another tool.</li>
 * </ul>
 */
public interface ToolGateway {

    /** Invokes one tool call and returns exactly one of the sealed outcomes. */
    ToolResult invoke(ToolCall call);
}
