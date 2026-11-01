package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

/** The node interceptor: step limit, started/completed/failed records, and zero node runs on every refusal. */
class NodeInterceptorTest {

    /** Records events; a failing sink throws for the action names it is told to fail on. */
    static final class Audit implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();
        final List<String> failOn = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            if (failOn.contains(event.action())) {
                throw new IllegalStateException("audit down");
            }
            events.add(event);
        }

        List<String> actions() {
            return events.stream().map(AuditEvent::action).toList();
        }
    }

    static final class Counting implements NodeAction<AgentState> {
        final AtomicInteger runs = new AtomicInteger();

        @Override
        public Map<String, Object> apply(AgentState state) {
            runs.incrementAndGet();
            return Map.of("out", "value");
        }
    }

    private static final AgentState STATE = new AgentState(Map.of());

    private NodeInterceptor interceptor(Audit audit, int maxSteps) {
        return new NodeInterceptor(GovernedLlmClientTest.AGENT, "run-1", maxSteps, audit, GovernedLlmClientTest.CLOCK);
    }

    @Test
    void anAdmittedNodeIsRecordedAsStartedThenCompletedAndReturnsItsResult() throws Exception {
        var audit = new Audit();
        var node = new Counting();

        var result = interceptor(audit, 3).wrap("plan", node).apply(STATE);

        assertEquals(Map.of("out", "value"), result);
        assertEquals(1, node.runs.get());
        assertEquals(List.of("agent.node.started", "agent.node.completed"), audit.actions());
        assertEquals("plan", audit.events.get(0).attributes().get("agent.node"));
        assertEquals("1", audit.events.get(0).attributes().get("agent.step"));
        assertEquals("run-1", audit.events.get(0).attributes().get("agent.run"));
    }

    @Test
    void theNodeAfterTheLimitIsRefusedAndNeverRuns() throws Exception {
        var audit = new Audit();
        var node = new Counting();
        var wrapped = interceptor(audit, 2).wrap("loop", node);

        wrapped.apply(STATE);
        wrapped.apply(STATE);
        assertThrows(NodeRefused.class, () -> wrapped.apply(STATE));

        assertEquals(2, node.runs.get());
        assertEquals("agent.node.refused", audit.actions().get(audit.actions().size() - 1));
    }

    @Test
    void failedAttemptsCountSoALoopCannotOutrunTheLimitByFailing() {
        var audit = new Audit();
        var attempts = new AtomicInteger();
        NodeAction<AgentState> failing = s -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        };
        var interceptor = interceptor(audit, 2);
        var wrapped = interceptor.wrap("flaky", failing);

        assertThrows(IllegalStateException.class, () -> wrapped.apply(STATE));
        assertThrows(IllegalStateException.class, () -> wrapped.apply(STATE));
        assertThrows(NodeRefused.class, () -> wrapped.apply(STATE));

        assertEquals(2, attempts.get());
        assertEquals(2, interceptor.stepsCounted());
        assertEquals(List.of("agent.node.started", "agent.node.failed", "agent.node.started", "agent.node.failed",
                "agent.node.refused"), audit.actions());
    }

    @Test
    void aNodeExceptionIsRethrownUnchanged() {
        var audit = new Audit();
        var boom = new IllegalStateException("boom");
        NodeAction<AgentState> failing = s -> {
            throw boom;
        };

        var thrown = assertThrows(IllegalStateException.class,
                () -> interceptor(audit, 3).wrap("n", failing).apply(STATE));

        assertSame(boom, thrown);
    }

    @Test
    void ifTheStartedRecordCannotBeWrittenTheNodeNeverRuns() {
        var audit = new Audit();
        audit.failOn.add("agent.node.started");
        var node = new Counting();

        assertThrows(NodeRefused.class, () -> interceptor(audit, 3).wrap("plan", node).apply(STATE));

        assertEquals(0, node.runs.get());
    }

    @Test
    void ifTheCompletedRecordCannotBeWrittenTheResultIsDropped() {
        var audit = new Audit();
        audit.failOn.add("agent.node.completed");
        var node = new Counting();

        assertThrows(NodeRefused.class, () -> interceptor(audit, 3).wrap("plan", node).apply(STATE));

        assertEquals(1, node.runs.get());
    }

    @Test
    void aFailedRefusalRecordDoesNotLiftTheRefusal() throws Exception {
        var audit = new Audit();
        audit.failOn.add("agent.node.refused");
        var node = new Counting();
        var wrapped = interceptor(audit, 1).wrap("n", node);
        wrapped.apply(STATE);

        assertThrows(NodeRefused.class, () -> wrapped.apply(STATE));

        assertEquals(1, node.runs.get());
    }

    @Test
    void auditEventsNeverCarryNodeInputOrOutput() throws Exception {
        var audit = new Audit();

        interceptor(audit, 3).wrap("plan", new Counting()).apply(new AgentState(Map.of("secret", "s3cr3t-input")));

        for (var event : audit.events) {
            assertFalse(event.attributes().values().stream().anyMatch(v -> v.contains("s3cr3t") || v.equals("value")));
        }
    }

    @Test
    void theLimitMustBeAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> interceptor(new Audit(), 0));
    }

    // ---------------------------------------------------------------- wall-clock limit

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        Instant now = GovernedLlmClientTest.NOW;
        @Override public ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void aNodeAfterTheDeadlineIsRefusedWithZeroRunsAndAnAuditRecord() throws Exception {
        var clock = new MovingClock();
        var audit = new Audit();
        var node = new Counting();
        var interceptor = new NodeInterceptor(GovernedLlmClientTest.AGENT, "run-1", 10, Duration.ofMinutes(5), audit, clock);
        var wrapped = interceptor.wrap("plan", node);

        wrapped.apply(STATE);
        clock.now = clock.now.plus(Duration.ofMinutes(5)); // exactly at the deadline is already too late
        var refused = assertThrows(NodeRefused.class, () -> wrapped.apply(STATE));

        assertEquals(1, node.runs.get());
        assertEquals(1, interceptor.stepsCounted(), "a refused node does not use a step");
        assertEquals(List.of("agent.node.started", "agent.node.completed", "agent.node.refused"), audit.actions());
        assertEquals(true, refused.getMessage().contains("wall-clock"));
    }

    @Test
    void justBeforeTheDeadlineStillRuns() throws Exception {
        var clock = new MovingClock();
        var node = new Counting();
        var wrapped = new NodeInterceptor(GovernedLlmClientTest.AGENT, "run-1", 10, Duration.ofMinutes(5), new Audit(), clock)
                .wrap("plan", node);
        clock.now = clock.now.plus(Duration.ofMinutes(5)).minusNanos(1);
        wrapped.apply(STATE);
        assertEquals(1, node.runs.get());
    }

    @Test
    void aNodeThatOutlastsTheDeadlineCompletesButTheNextOneIsRefused() throws Exception {
        var clock = new MovingClock();
        var audit = new Audit();
        var slow = new NodeAction<AgentState>() {
            @Override public Map<String, Object> apply(AgentState state) {
                clock.now = clock.now.plus(Duration.ofMinutes(10));
                return Map.of("out", "late");
            }
        };
        var interceptor = new NodeInterceptor(GovernedLlmClientTest.AGENT, "run-1", 10, Duration.ofMinutes(5), audit, clock);
        assertEquals(Map.of("out", "late"), interceptor.wrap("slow", slow).apply(STATE));
        var next = new Counting();
        assertThrows(NodeRefused.class, () -> interceptor.wrap("next", next).apply(STATE));
        assertEquals(0, next.runs.get());
    }

    @Test
    void aFailedRefusalRecordStillRefusesTheNode() {
        var clock = new MovingClock();
        var audit = new Audit();
        audit.failOn.add("agent.node.refused");
        var node = new Counting();
        var wrapped = new NodeInterceptor(GovernedLlmClientTest.AGENT, "run-1", 10, Duration.ofSeconds(1), audit, clock)
                .wrap("plan", node);
        clock.now = clock.now.plusSeconds(2);
        assertThrows(NodeRefused.class, () -> wrapped.apply(STATE));
        assertEquals(0, node.runs.get());
    }

    @Test
    void forLimitsAppliesBothTheIterationAndTheWallClockBound() throws Exception {
        var clock = new MovingClock();
        var limits = new ExecutionLimits(2, Duration.ofMinutes(1), 1_000L);
        var interceptor = NodeInterceptor.forLimits(GovernedLlmClientTest.AGENT, "run-1", limits, new Audit(), clock);
        var node = new Counting();
        var wrapped = interceptor.wrap("plan", node);
        wrapped.apply(STATE);
        wrapped.apply(STATE);
        assertThrows(NodeRefused.class, () -> wrapped.apply(STATE)); // iterations
        var again = NodeInterceptor.forLimits(GovernedLlmClientTest.AGENT, "run-2", limits, new Audit(), clock)
                .wrap("plan", node);
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThrows(NodeRefused.class, () -> again.apply(STATE)); // wall clock
        assertEquals(2, node.runs.get());
    }

    @Test
    void anInvalidDeadlineOrMissingLimitsAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new NodeInterceptor(GovernedLlmClientTest.AGENT, "r", 1,
                Duration.ZERO, new Audit(), GovernedLlmClientTest.CLOCK));
        assertThrows(IllegalArgumentException.class, () -> new NodeInterceptor(GovernedLlmClientTest.AGENT, "r", 1,
                Duration.ofSeconds(-1), new Audit(), GovernedLlmClientTest.CLOCK));
        assertThrows(NullPointerException.class, () -> NodeInterceptor.forLimits(GovernedLlmClientTest.AGENT, "r", null,
                new Audit(), GovernedLlmClientTest.CLOCK));
    }
}
