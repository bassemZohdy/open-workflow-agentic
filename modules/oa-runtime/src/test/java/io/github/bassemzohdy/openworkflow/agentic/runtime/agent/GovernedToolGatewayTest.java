package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GovernedToolGatewayTest {
    private static final String INPUT = "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}},\"additionalProperties\":false}";
    private static final String OUTPUT = "{\"type\":\"object\",\"required\":[\"result\"],\"properties\":{\"result\":{\"type\":\"string\"}}}";
    private static final AgentTaskContract TASK_WITH_TOOL = new AgentTaskContract(TASK.id(), TASK.goal(), Set.of("search"),
            TASK.permittedDataScopes(), TASK.riskTier(), TASK.limits(), TASK.requestingSubject(), TASK.delegationGrantId());
    private static final ToolCall CALL = new ToolCall(TASK.id(), "search", "{\"query\":\"x\"}");
    private static final GovernedToolGateway.Approval APPROVAL = new GovernedToolGateway.Approval(AGENT, TASK_WITH_TOOL, 100);

    private static final class Rig {
        final AtomicReference<GovernedToolGateway.Approval> approval = new AtomicReference<>(APPROVAL);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger decisions = new AtomicInteger();
        final AtomicInteger audits = new AtomicInteger();
        final java.util.List<String> boundaryEvents = new java.util.ArrayList<>();
        final java.util.List<io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent> records = new java.util.ArrayList<>();
        java.util.function.Consumer<String> duringBoundaryAudit = action -> { };
        GovernedToolGateway.Tool tool = args -> { calls.incrementAndGet(); return "{\"result\":\"ok\"}"; };
        DecisionOutcome outcome = DecisionOutcome.ALLOW;
        Runnable duringAudit = () -> { };
        boolean mutating;
        Duration timeout = Duration.ofSeconds(2);
        GovernedToolGateway gateway() {
            var registration = new GovernedToolGateway.Registration(INPUT, OUTPUT, mutating, timeout, tool);
            var pep = new Pep(request -> { decisions.incrementAndGet(); return new Decision("test", outcome, "test", POLICY, NOW); },
                    event -> { audits.incrementAndGet(); duringAudit.run(); }, Duration.ofSeconds(5), CLOCK);
            return new GovernedToolGateway(Map.of("search", registration), () -> Optional.ofNullable(approval.get()), pep,
                    event -> { records.add(event); boundaryEvents.add(event.action()); duringBoundaryAudit.accept(event.action()); }, CLOCK);
        }
    }

    @Test void allowedCallValidatesAndAuditsBeforeOneInvocation() {
        var rig = new Rig();
        var completed = assertInstanceOf(ToolResult.Completed.class, rig.gateway().invoke(CALL));
        assertEquals("{\"result\":\"ok\"}", completed.output());
        assertEquals(1, rig.decisions.get()); assertEquals(1, rig.audits.get()); assertEquals(1, rig.calls.get());
        assertEquals(java.util.List.of("agent.tool.started", "agent.tool.completed"), rig.boundaryEvents);
        assertEquals(rig.records.get(0).attributes().get("agent.tool.attempt"),
                rig.records.get(1).attributes().get("agent.tool.attempt"));
        assertFalse(rig.records.toString().contains("query"));
        assertFalse(rig.records.toString().contains("result"));
    }

    @Test void missingOrMismatchedApprovalAndUnlistedToolsNeverCall() {
        var rig = new Rig(); var gateway = rig.gateway();
        rig.approval.set(null); assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        rig.approval.set(new GovernedToolGateway.Approval(AGENT, TASK, 100));
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        rig.approval.set(new GovernedToolGateway.Approval(AGENT, TASK_WITH_TOOL, 0));
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        assertEquals(0, rig.calls.get()); assertEquals(0, rig.decisions.get());
    }

    @Test void invalidOrAmbiguousArgumentsAreRejectedBeforePolicyAndTool() {
        var rig = new Rig(); var gateway = rig.gateway();
        for (String bad : new String[] {"{}", "[]", "{\"query\":3}", "{\"query\":\"x\",\"query\":\"y\"}",
                "{\"query\":\"x\"} {}", "{\"query\":\"x\",\"extra\":1}"})
            assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(new ToolCall(TASK.id(), "search", bad)), bad);
        assertEquals(0, rig.calls.get()); assertEquals(0, rig.decisions.get());
    }

    @Test void deniedOrUnauditedDecisionsNeverInvokeTheTool() {
        var denied = new Rig(); denied.outcome = DecisionOutcome.DENY;
        assertInstanceOf(ToolResult.Rejected.class, denied.gateway().invoke(CALL));
        assertEquals(0, denied.calls.get()); assertEquals(1, denied.audits.get());
        var broken = new Rig(); broken.duringAudit = () -> { throw new IllegalStateException("private"); };
        assertInstanceOf(ToolResult.Rejected.class, broken.gateway().invoke(CALL));
        assertEquals(0, broken.calls.get());
    }

    @Test void approvalLostDuringAuditRefusesAndAfterToolWithholdsOutput() {
        var before = new Rig(); before.duringAudit = () -> before.approval.set(null);
        assertInstanceOf(ToolResult.Rejected.class, before.gateway().invoke(CALL)); assertEquals(0, before.calls.get());
        var after = new Rig(); after.mutating = true;
        after.tool = args -> { after.calls.incrementAndGet(); after.approval.set(null); return "{\"result\":\"private\"}"; };
        var failure = assertInstanceOf(ToolResult.Failed.class, after.gateway().invoke(CALL));
        assertTrue(failure.effectMayHaveHappened()); assertFalse(failure.reason().contains("private"));
        assertEquals(1, after.calls.get());
    }

    @Test void invalidOutputAndExceptionsWithholdOutputAndPreserveEffectUncertainty() {
        var bad = new Rig(); bad.mutating = true; bad.tool = args -> "{\"result\":3}";
        assertTrue(assertInstanceOf(ToolResult.Failed.class, bad.gateway().invoke(CALL)).effectMayHaveHappened());
        var broken = new Rig(); broken.tool = args -> { throw new IllegalStateException("secret"); };
        var result = assertInstanceOf(ToolResult.Failed.class, broken.gateway().invoke(CALL));
        assertFalse(result.effectMayHaveHappened()); assertFalse(result.reason().contains("secret"));
    }

    @Test void schemasCannotResolveExternalOrDynamicReferences() {
        for (String key : new String[] {"$ref", "$dynamicRef", "$recursiveRef", "$id", "$schema"}) {
            String schema = "{\"" + key + "\":\"https://example.invalid/schema\"}";
            assertThrows(IllegalArgumentException.class,
                    () -> new GovernedToolGateway.Registration(schema, OUTPUT, false, Duration.ofSeconds(1), args -> "{}"));
        }
    }

    @Test void missingStartAuditOrApprovalLostDuringItPreventsTheTool() {
        var unavailable = new Rig();
        unavailable.duringBoundaryAudit = action -> { if (action.equals("agent.tool.started")) throw new IllegalStateException(); };
        assertInstanceOf(ToolResult.Rejected.class, unavailable.gateway().invoke(CALL));
        assertEquals(0, unavailable.calls.get());
        var lost = new Rig();
        lost.duringBoundaryAudit = action -> { if (action.equals("agent.tool.started")) lost.approval.set(null); };
        assertInstanceOf(ToolResult.Rejected.class, lost.gateway().invoke(CALL));
        assertEquals(0, lost.calls.get());
    }

    @Test void completionAuditFailureOrApprovalLossWithholdsTheAnswer() {
        for (boolean revoke : new boolean[] {false, true}) {
            var rig = new Rig(); rig.mutating = true;
            rig.duringBoundaryAudit = action -> {
                if (action.equals("agent.tool.completed")) {
                    if (revoke) rig.approval.set(null); else throw new IllegalStateException();
                }
            };
            assertTrue(assertInstanceOf(ToolResult.Failed.class, rig.gateway().invoke(CALL)).effectMayHaveHappened());
            assertEquals(1, rig.calls.get());
            assertTrue(rig.boundaryEvents.contains("agent.tool.failed"));
        }
    }

    @Test void schemaFormatsAreAssertionsRatherThanAnnotations() {
        var rig = new Rig();
        var reg = new GovernedToolGateway.Registration(
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"format\":\"email\"}}}",
                OUTPUT, false, Duration.ofSeconds(1), rig.tool);
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var gateway = new GovernedToolGateway(Map.of("search", reg), () -> Optional.of(APPROVAL), pep, event -> { }, CLOCK);
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        assertEquals(0, rig.calls.get());
    }

    @Test void interruptionOfTheCallerIsPreservedAndMutatingOutcomeIsUncertain() throws Exception {
        var rig = new Rig(); rig.mutating = true;
        var started = new java.util.concurrent.CountDownLatch(1);
        rig.tool = args -> { started.countDown(); new java.util.concurrent.CountDownLatch(1).await(); return "{}"; };
        var result = new AtomicReference<ToolResult>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = new Thread(() -> {
            result.set(rig.gateway().invoke(CALL));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        thread.interrupt(); thread.join(5000);
        assertFalse(thread.isAlive());
        assertTrue(assertInstanceOf(ToolResult.Failed.class, result.get()).effectMayHaveHappened());
        assertTrue(interrupted.get());
    }

    @Test void timeoutWithholdsLateOutputAndDoesNotAssumeTheToolStopped() throws Exception {
        var rig = new Rig(); rig.mutating = true; rig.timeout = Duration.ofMillis(100);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        rig.tool = args -> {
            rig.calls.incrementAndGet();
            try {
                while (true) try { release.await(); break; } catch (InterruptedException ignored) { }
                return "{\"result\":\"late private output\"}";
            } finally { finished.countDown(); }
        };
        try {
            var result = assertInstanceOf(ToolResult.Failed.class, rig.gateway().invoke(CALL));
            assertTrue(result.effectMayHaveHappened());
            assertEquals(1, rig.calls.get());
            assertEquals(1, finished.getCount(), "cancellation is not proof that the tool stopped");
            assertFalse(result.reason().contains("private"));
        } finally { release.countDown(); assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
    }

    @Test void stalledTimedOutToolsCannotCreateUnboundedWorkers() throws Exception {
        var rig = new Rig(); rig.mutating = true; rig.timeout = Duration.ofMillis(100);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(GovernedToolGateway.MAX_IN_FLIGHT);
        var finished = new java.util.concurrent.CountDownLatch(GovernedToolGateway.MAX_IN_FLIGHT);
        rig.tool = args -> {
            rig.calls.incrementAndGet(); started.countDown();
            try { while (true) try { release.await(); break; } catch (InterruptedException ignored) { } }
            finally { finished.countDown(); }
            return "{\"result\":\"late\"}";
        };
        try {
            var gateway = rig.gateway();
            for (int i = 0; i < GovernedToolGateway.MAX_IN_FLIGHT; i++)
                assertInstanceOf(ToolResult.Failed.class, gateway.invoke(CALL));
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
            assertEquals(GovernedToolGateway.MAX_IN_FLIGHT, rig.calls.get());
        } finally { release.countDown(); assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
    }

    @Test void registrationRequiresABoundedPositiveTimeout() {
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofNanos(1), Duration.ofMinutes(2)})
            assertThrows(IllegalArgumentException.class,
                    () -> new GovernedToolGateway.Registration(INPUT, OUTPUT, false, timeout, args -> "{}"));
    }

    @Test void approvalUnavailableOnTheWorkerRefusesBeforeCallingTheTool() {
        var rig = new Rig();
        var registration = new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(1), rig.tool);
        var caller = Thread.currentThread();
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var gateway = new GovernedToolGateway(Map.of("search", registration),
                () -> Thread.currentThread() == caller ? Optional.of(APPROVAL) : Optional.empty(), pep, event -> { }, CLOCK);
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        assertEquals(0, rig.calls.get());
    }

    @Test void aTimedOutWorkerCannotStartTheToolAfterAStalledApprovalLookup() throws Exception {
        var caller = Thread.currentThread();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var lookupFinished = new java.util.concurrent.CountDownLatch(1);
        var invoked = new java.util.concurrent.CountDownLatch(1);
        var reg = new GovernedToolGateway.Registration(INPUT, OUTPUT, true, Duration.ofMillis(150),
                args -> { invoked.countDown(); return "{\"result\":\"late\"}"; });
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var gateway = new GovernedToolGateway(Map.of("search", reg), () -> {
            if (Thread.currentThread() != caller) {
                entered.countDown();
                try { while (true) try { release.await(); break; } catch (InterruptedException ignored) { } }
                finally { lookupFinished.countDown(); }
            }
            return Optional.of(APPROVAL);
        }, pep, event -> { }, CLOCK);
        try {
            assertTrue(assertInstanceOf(ToolResult.Failed.class, gateway.invoke(CALL)).effectMayHaveHappened());
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertTrue(lookupFinished.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(invoked.await(300, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Test void eachToolCallUsesAFreshThreadWithoutInheritedCallerState() {
        var local = new ThreadLocal<String>();
        var inherited = new InheritableThreadLocal<String>();
        inherited.set("caller-private");
        var first = new AtomicReference<Thread>();
        var second = new AtomicReference<Thread>();
        var leaked = new AtomicReference<String>();
        var rig = new Rig();
        rig.tool = args -> {
            if (rig.calls.getAndIncrement() == 0) {
                first.set(Thread.currentThread());
                if (inherited.get() != null) leaked.set(inherited.get());
                local.set("previous-call-private");
            } else {
                second.set(Thread.currentThread());
                if (local.get() != null) leaked.set(local.get());
                if (inherited.get() != null) leaked.set(inherited.get());
            }
            return "{\"result\":\"ok\"}";
        };
        try {
            var gateway = rig.gateway();
            assertInstanceOf(ToolResult.Completed.class, gateway.invoke(CALL));
            assertInstanceOf(ToolResult.Completed.class, gateway.invoke(CALL));
            assertNotNull(first.get()); assertNotNull(second.get());
            assertNotSame(first.get(), second.get());
            assertNull(leaked.get());
        } finally { inherited.remove(); }
    }
}
