package io.github.bassemzohdy.openworkflow.agentic.contracts.doubles;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskRecord;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.TaskStore;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolGateway;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reference gateway for tests. It applies the order the port documents: the task must exist, the tool must be in its
 * contract, the policy decision point must allow, then the tool runs. Any exception from the tool is a
 * {@link ToolResult.Failed} whose effect is treated as possibly applied.
 */
public final class GuardedToolGateway implements ToolGateway {

    static final PolicyRef GATEWAY_RULE = new PolicyRef("oa.tool-gateway", "1");

    private final TaskStore tasks;
    private final PolicyDecisionPoint pdp;
    private final Function<ToolCall, String> tool;

    public GuardedToolGateway(TaskStore tasks, PolicyDecisionPoint pdp, Function<ToolCall, String> tool) {
        this.tasks = tasks;
        this.pdp = pdp;
        this.tool = tool;
    }

    @Override
    public ToolResult invoke(ToolCall call) {
        Optional<AgentTaskRecord> task = tasks.find(call.taskId());
        if (task.isEmpty()) {
            return refuse("unknown agent task");
        }
        AgentTaskRecord record = task.get();
        if (!record.contract().permitsTool(call.toolName())) {
            return refuse("tool is not in the task's allow-list");
        }
        Decision decision = pdp.decide(new AuthorizationRequest(record.owner(), ActionKind.TOOL_CALL,
                "tool:" + call.toolName(), call.taskId()));
        if (!decision.allows()) {
            return new ToolResult.Rejected(decision);
        }
        try {
            return new ToolResult.Completed(tool.apply(call));
        } catch (RuntimeException e) {
            return new ToolResult.Failed(e.getClass().getSimpleName(), true);
        }
    }

    private static ToolResult refuse(String reason) {
        return new ToolResult.Rejected(new Decision("tool-gateway", DecisionOutcome.DENY, reason, GATEWAY_RULE,
                Instant.EPOCH));
    }
}
