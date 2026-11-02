package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;

/**
 * Wraps graph nodes with a step limit, an optional wall-clock limit and an audit record (WP-201 node interceptors).
 *
 * <p>For each node execution, in this order:
 * <ol>
 *   <li>the wall-clock deadline, when there is one, must not have passed; otherwise the node is refused;</li>
 *   <li>the step is counted against {@code maxSteps}; when the limit is already reached the node is refused;</li>
 *   <li>{@code agent.node.started} is recorded; if that record cannot be written the node is refused;</li>
 *   <li>the deadline is rechecked after the started audit; the node runs only while it is still current;</li>
 *   <li>the deadline is rechecked after the action, then {@code agent.node.completed} is recorded and the deadline is
 *       checked again before the result is returned. If the
 *       write fails the result is dropped and the step fails, so graph state never advances on an unrecorded step.
 *       A node that throws is recorded as {@code agent.node.failed} (best effort) and its exception is rethrown.</li>
 * </ol>
 * A node refused before execution does not run. A deadline refusal after execution withholds the result but cannot
 * undo work the action already performed. A limit refusal is recorded as {@code agent.node.refused}; if that record
 * fails too, the refusal stands.
 *
 * <p>The deadline is {@code startedAt + maxWallClock}, fixed when the interceptor is made. It is checked when a node is
 * admitted and before its result is released. A node already running is not interrupted, but its late result is
 * withheld and the next node is refused. A
 * node refused before admission does not use a step. If the started audit takes the request past its deadline, the
 * attempt remains counted and the action is refused before it runs.
 *
 * <p>Every attempt counts, including retries and failures, so a loop cannot run past the limit by failing. One
 * interceptor is made for one run; its local counter is not shared. When provided, a durable budget also counts
 * across runtimes. Policy checks for what a node does (tool, model) stay
 * in the PEP boundaries the node calls, which this class does not replace.
 *
 * <p>The audit event carries the node name, run id and step number only. It never carries node input or output.
 */
public final class NodeInterceptor {
    /** Private, per-approved-task persistent admission and deadline checks. */
    public interface DurableBudget { void admit(); void requireCurrent(); }

    private final Subject actor;
    private final String runId;
    private final int maxSteps;
    private final AuditSink audit;
    private final Clock clock;
    private final AtomicInteger steps = new AtomicInteger();
    private final Instant deadline;
    private final Duration maxWallClock;
    private DurableBudget durable;

    /** Step limit only, no wall-clock limit. */
    public NodeInterceptor(Subject actor, String runId, int maxSteps, AuditSink audit, Clock clock) {
        this(actor, runId, maxSteps, null, audit, clock);
    }

    /** The step and wall-clock bounds an approved task states; the clock starts now. */
    public static NodeInterceptor forLimits(Subject actor, String runId, ExecutionLimits limits, AuditSink audit,
                                            Clock clock) {
        Objects.requireNonNull(limits, "limits");
        return new NodeInterceptor(actor, runId, limits.maxIterations(), limits.maxWallClock(), audit, clock);
    }

    /** Uses the durable budget in addition to this interceptor's local bounds. */
    public static NodeInterceptor forLimits(Subject actor, String runId, ExecutionLimits limits, AuditSink audit,
                                            Clock clock, DurableBudget durable) {
        var interceptor = forLimits(actor, runId, limits, audit, clock);
        interceptor.durable = Objects.requireNonNull(durable, "durable");
        return interceptor;
    }

    /** @param maxWallClock the time from now after which no node is admitted, or null for none */
    public NodeInterceptor(Subject actor, String runId, int maxSteps, Duration maxWallClock, AuditSink audit,
                           Clock clock) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.runId = Checks.requireText("runId", runId, 128);
        this.maxSteps = (int) Checks.requireInRange("maxSteps", maxSteps, 1L, 10_000L);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxWallClock != null && (maxWallClock.isZero() || maxWallClock.isNegative())) {
            throw new IllegalArgumentException("maxWallClock must be positive");
        }
        this.maxWallClock = maxWallClock;
        this.deadline = maxWallClock == null ? null : clock.instant().plus(maxWallClock);
    }

    /** Local attempts counted so far; a refusal by a durable budget may occur after this local count increments. */
    public int stepsCounted() {
        return steps.get();
    }

    public <S extends AgentState> NodeAction<S> wrap(String node, NodeAction<S> action) {
        Checks.requireText("node", node, 128);
        Objects.requireNonNull(action, "action");
        return state -> {
            int step = admit(node);
            record("agent.node.started", node, step, null, true);
            requireCurrentDeadline(node, step);
            Map<String, Object> result;
            try {
                result = action.apply(state);
            } catch (Exception | Error e) {
                record("agent.node.failed", node, step, e.getClass().getSimpleName(), false);
                throw e;
            }
            requireCurrentDeadline(node, step, true);
            record("agent.node.completed", node, step, null, true);
            requireCurrentDeadline(node, step, true);
            return result;
        };
    }

    /** Trace setup and completion may block; the ordinary deadline/budget checks surround them. */
    <S extends AgentState> NodeAction<S> wrap(String node, NodeAction<S> action, AgentNodeTelemetry telemetry) {
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(action, "action");
        return wrap(node, state -> telemetry.call(() -> {
            // Recheck after trace setup and immediately before the node action.
            requireCurrentDeadline(node, steps.get());
            return action.apply(state);
        }));
    }

    private int admit(String node) {
        requireCurrentDeadline(node, steps.get());
        while (true) {
            int current = steps.get();
            if (current >= maxSteps) {
                record("agent.node.refused", node, current, "step limit " + maxSteps + " reached", false);
                throw new NodeRefused("step limit " + maxSteps + " reached before node " + node);
            }
            if (steps.compareAndSet(current, current + 1)) {
                if (durable != null) {
                    try { durable.admit(); }
                    catch (RuntimeException failure) {
                        record("agent.node.refused", node, current, "durable budget refused or unavailable", false);
                        throw failure;
                    }
                }
                return current + 1;
            }
        }
    }

    private void requireCurrentDeadline(String node, int step) {
        requireCurrentDeadline(node, step, false);
    }

    private void requireCurrentDeadline(String node, int step, boolean result) {
        if (durable != null) {
            try { durable.requireCurrent(); }
            catch (RuntimeException failure) {
                record("agent.node.refused", node, step, "durable deadline refused or unavailable", false);
                throw failure;
            }
        }
        if (deadline != null && !clock.instant().isBefore(deadline)) {
            record("agent.node.refused", node, step, "wall-clock limit " + maxWallClock + " reached", false);
            throw new NodeRefused("wall-clock limit " + maxWallClock + (result ? " reached before releasing result of node " : " reached before node ") + node);
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
