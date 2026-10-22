package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;

/**
 * Wraps graph nodes with a step limit and an audit record (WP-201 node interceptors).
 *
 * <p>For each node execution, in this order:
 * <ol>
 *   <li>the step is counted against {@code maxSteps}; when the limit is already reached the node is refused;</li>
 *   <li>{@code agent.node.started} is recorded; if that record cannot be written the node is refused;</li>
 *   <li>the node runs;</li>
 *   <li>{@code agent.node.completed} is recorded and the result is returned only after that write succeeds. If the
 *       write fails the result is dropped and the step fails, so graph state never advances on an unrecorded step.
 *       A node that throws is recorded as {@code agent.node.failed} (best effort) and its exception is rethrown.</li>
 * </ol>
 * A refused node is never run: zero executions of the node's action. A refusal for the limit is recorded as
 * {@code agent.node.refused}; if that record fails too, the refusal stands.
 *
 * <p>Every attempt counts, including retries and failures, so a loop cannot run past the limit by failing. One
 * interceptor is made for one run; its counter is not shared. Policy checks for what a node does (tool, model) stay
 * in the PEP boundaries the node calls, which this class does not replace.
 *
 * <p>The audit event carries the node name, run id and step number only. It never carries node input or output.
 */
public final class NodeInterceptor {

    private final Subject actor;
    private final String runId;
    private final int maxSteps;
    private final AuditSink audit;
    private final Clock clock;
    private final AtomicInteger steps = new AtomicInteger();

    public NodeInterceptor(Subject actor, String runId, int maxSteps, AuditSink audit, Clock clock) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.runId = Checks.requireText("runId", runId, 128);
        this.maxSteps = (int) Checks.requireInRange("maxSteps", maxSteps, 1L, 10_000L);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Steps counted so far. Attempts refused at the limit are not counted. */
    public int stepsCounted() {
        return steps.get();
    }

    public <S extends AgentState> NodeAction<S> wrap(String node, NodeAction<S> action) {
        Checks.requireText("node", node, 128);
        Objects.requireNonNull(action, "action");
        return state -> {
            int step = admit(node);
            record("agent.node.started", node, step, null, true);
            Map<String, Object> result;
            try {
                result = action.apply(state);
            } catch (Exception | Error e) {
                record("agent.node.failed", node, step, e.getClass().getSimpleName(), false);
                throw e;
            }
            record("agent.node.completed", node, step, null, true);
            return result;
        };
    }

    private int admit(String node) {
        while (true) {
            int current = steps.get();
            if (current >= maxSteps) {
                record("agent.node.refused", node, current, "step limit " + maxSteps + " reached", false);
                throw new NodeRefused("step limit " + maxSteps + " reached before node " + node);
            }
            if (steps.compareAndSet(current, current + 1)) {
                return current + 1;
            }
        }
    }

    /** Writes one audit event; when {@code required} a failed write refuses the step, otherwise it is swallowed. */
    private void record(String action, String node, int step, String detail, boolean required) {
        try {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("agent.run", runId);
            attributes.put("agent.node", node);
            attributes.put("agent.step", Integer.toString(step));
            if (detail != null) {
                attributes.put("agent.detail", detail.substring(0, Math.min(detail.length(), 200)));
            }
            AuditEvent event = AuditDraft.of(UlidGenerator.shared().next(), clock.instant(), actor, action,
                    "agent-node", runId + "/" + node, attributes);
            audit.record(event);
        } catch (RuntimeException e) {
            if (required) {
                throw new NodeRefused("audit record " + action + " failed for node " + node);
            }
        }
    }
}
