package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ApprovedToolProfile;
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
    private static final String IMPLEMENTATION = "a".repeat(64);
    private static final AgentTaskContract TASK_WITH_TOOL = new AgentTaskContract(TASK.id(), TASK.goal(), Set.of("search"),
            TASK.permittedDataScopes(), TASK.riskTier(), TASK.limits(), TASK.requestingSubject(), TASK.delegationGrantId());
    private static final ToolCall CALL = new ToolCall(TASK.id(), "search", "{\"query\":\"x\"}");
    private static final GovernedToolGateway.Approval APPROVAL = new GovernedToolGateway.Approval(AGENT, TASK_WITH_TOOL, 100);
    private static final GovernedToolGateway.ToolBudget TEST_BUDGET = new GovernedToolGateway.ToolBudget() {
        @Override public void admit(GovernedToolGateway.Approval approval) { }
        @Override public void requireCurrent(GovernedToolGateway.Approval approval) { }
        @Override public void requireProfile(GovernedToolGateway.Approval approval, ApprovedToolProfile profile) { }
    };

    private static ApprovedToolProfile profile(Map<String, GovernedToolGateway.Registration> registry) {
        return new ApprovedToolProfile("test-profile", "1", GovernedToolGateway.configurationFingerprint(registry),
                AGENT, Set.of("search"), 100, Duration.ofSeconds(2));
    }

    private static final class Rig {
        final AtomicReference<GovernedToolGateway.Approval> approval = new AtomicReference<>(APPROVAL);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger decisions = new AtomicInteger();
        final AtomicInteger audits = new AtomicInteger();
        final AtomicInteger admissions = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean budgetCurrent = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.List<String> boundaryEvents = new java.util.ArrayList<>();
        final java.util.List<io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent> records = new java.util.ArrayList<>();
        java.util.function.Consumer<String> duringBoundaryAudit = action -> { };
        GovernedToolGateway.Tool tool = args -> { calls.incrementAndGet(); return "{\"result\":\"ok\"}"; };
        ToolCallTelemetry telemetry = ToolCallTelemetry.noop();
        DecisionOutcome outcome = DecisionOutcome.ALLOW;
        Runnable duringAudit = () -> { };
        boolean mutating;
        Duration timeout = Duration.ofSeconds(2);
        GovernedToolGateway gateway() {
            var registration = new GovernedToolGateway.Registration(INPUT, OUTPUT, mutating, timeout, IMPLEMENTATION, tool);
            var registry = Map.of("search", registration);
            var pep = new Pep(request -> { decisions.incrementAndGet(); return new Decision("test", outcome, "test", POLICY, NOW); },
                    event -> { audits.incrementAndGet(); duringAudit.run(); }, Duration.ofSeconds(5), CLOCK);
            return new GovernedToolGateway(registry, () -> Optional.ofNullable(approval.get()), profile(registry), pep,
                    event -> { records.add(event); boundaryEvents.add(event.action()); duringBoundaryAudit.accept(event.action()); },
                    new GovernedToolGateway.ToolBudget() {
                        @Override public void admit(GovernedToolGateway.Approval approval) {
                            if (!budgetCurrent.get()) throw new NodeRefused("test budget unavailable");
                            admissions.incrementAndGet();
                        }
                        @Override public void requireCurrent(GovernedToolGateway.Approval approval) {
                            if (!budgetCurrent.get()) throw new NodeRefused("test budget unavailable");
                        }
                        @Override public void requireProfile(GovernedToolGateway.Approval approval, ApprovedToolProfile profile) {
                            if (!budgetCurrent.get()) throw new NodeRefused("test profile unavailable");
                        }
                    }, CLOCK, null, telemetry);
        }
    }

    @Test void allowedCallValidatesAndAuditsBeforeOneInvocation() {
        var rig = new Rig();
        var completed = assertInstanceOf(ToolResult.Completed.class, rig.gateway().invoke(CALL));
        assertEquals("{\"result\":\"ok\"}", completed.output());
        assertEquals(1, rig.decisions.get()); assertEquals(1, rig.audits.get()); assertEquals(1, rig.calls.get());
        assertEquals(1, rig.admissions.get());
        assertEquals(java.util.List.of("agent.tool.started", "agent.tool.completed"), rig.boundaryEvents);
        assertEquals(rig.records.get(0).attributes().get("agent.tool.attempt"),
                rig.records.get(1).attributes().get("agent.tool.attempt"));
        assertFalse(rig.records.toString().contains("query"));
        assertFalse(rig.records.toString().contains("result"));
    }

    @Test void toolWorkerGetsOnlyTheTraceAndEmitsANonSensitiveChildSpan() {
        var exporter=io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        try(var sdk=io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()) {
            var rig=new Rig();rig.telemetry=new ToolCallTelemetry(sdk.get("tools"));
            var parent=sdk.get("caller").spanBuilder("parent").startSpan();
            var inherited=new InheritableThreadLocal<String>();inherited.set("private-local");
            var seen=new AtomicReference<io.opentelemetry.api.trace.SpanContext>();
            rig.tool=args->{
                seen.set(io.opentelemetry.api.trace.Span.current().getSpanContext());
                assertNull(inherited.get());
                assertNull(io.opentelemetry.api.baggage.Baggage.current().getEntryValue("secret"));
                rig.calls.incrementAndGet();return "{\"result\":\"private-output\"}";
            };
            var baggage=io.opentelemetry.api.baggage.Baggage.builder().put("secret","private-baggage").build();
            try(var scope=io.opentelemetry.context.Context.current().with(parent).with(baggage).makeCurrent()) {
                assertInstanceOf(ToolResult.Completed.class,rig.gateway().invoke(CALL));
                assertEquals(parent.getSpanContext(),io.opentelemetry.api.trace.Span.current().getSpanContext());
            } finally {parent.end();inherited.remove();}
            var tool=exporter.getFinishedSpanItems().stream().filter(s->s.getName().equals("oa.tool.call")).findFirst().orElseThrow();
            assertEquals(parent.getSpanContext().getSpanId(),tool.getParentSpanId());
            assertEquals(parent.getSpanContext().getTraceId(),tool.getTraceId());
            assertEquals(tool.getSpanContext(),seen.get());
            assertEquals(Set.of(io.opentelemetry.api.common.AttributeKey.stringKey("oa.tool.schema"),
                    io.opentelemetry.api.common.AttributeKey.stringKey("oa.tool.outcome")),tool.getAttributes().asMap().keySet());
            assertFalse(tool.toString().contains("private"));assertTrue(tool.getEvents().isEmpty());
            assertEquals(1,rig.calls.get());
        }
    }

    @Test void approvalLostWhileTracingStartsCannotReachTheTool() {
        var rig=new Rig();
        var tracer=(io.opentelemetry.api.trace.Tracer) java.lang.reflect.Proxy.newProxyInstance(
                io.opentelemetry.api.trace.Tracer.class.getClassLoader(),new Class<?>[]{io.opentelemetry.api.trace.Tracer.class},
                (p,m,a)->{rig.approval.set(null);throw new IllegalStateException("private");});
        rig.telemetry=new ToolCallTelemetry(tracer);
        assertInstanceOf(ToolResult.Rejected.class,rig.gateway().invoke(CALL));
        assertEquals(0,rig.calls.get());
    }

    @Test void unavailableTracingDoesNotRetryTheTool() {
        var rig=new Rig();
        var tracer=(io.opentelemetry.api.trace.Tracer) java.lang.reflect.Proxy.newProxyInstance(
                io.opentelemetry.api.trace.Tracer.class.getClassLoader(),new Class<?>[]{io.opentelemetry.api.trace.Tracer.class},
                (p,m,a)->{throw new IllegalStateException("private");});
        rig.telemetry=new ToolCallTelemetry(tracer);
        assertInstanceOf(ToolResult.Completed.class,rig.gateway().invoke(CALL));
        assertEquals(1,rig.calls.get());
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
        var after = new Rig(); after.mutating = false;
        after.tool = args -> { after.calls.incrementAndGet(); after.approval.set(null); return "{\"result\":\"private\"}"; };
        var failure = assertInstanceOf(ToolResult.Failed.class, after.gateway().invoke(CALL));
        assertFalse(failure.effectMayHaveHappened()); assertFalse(failure.reason().contains("private"));
        assertEquals(1, after.calls.get());
    }

    @Test void invalidOutputAndExceptionsWithholdOutput() {
        var bad = new Rig(); bad.mutating = false; bad.tool = args -> "{\"result\":3}";
        assertFalse(assertInstanceOf(ToolResult.Failed.class, bad.gateway().invoke(CALL)).effectMayHaveHappened());
        var broken = new Rig(); broken.tool = args -> { throw new IllegalStateException("secret"); };
        var result = assertInstanceOf(ToolResult.Failed.class, broken.gateway().invoke(CALL));
        assertFalse(result.effectMayHaveHappened()); assertFalse(result.reason().contains("secret"));
    }

    @Test void schemasCannotResolveExternalOrDynamicReferences() {
        for (String key : new String[] {"$ref", "$dynamicRef", "$recursiveRef", "$id", "$schema"}) {
            String schema = "{\"" + key + "\":\"https://example.invalid/schema\"}";
            assertThrows(IllegalArgumentException.class,
                    () -> new GovernedToolGateway.Registration(schema, OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, args -> "{}"));
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
            var rig = new Rig(); rig.mutating = false;
            rig.duringBoundaryAudit = action -> {
                if (action.equals("agent.tool.completed")) {
                    if (revoke) rig.approval.set(null); else throw new IllegalStateException();
                }
            };
            assertFalse(assertInstanceOf(ToolResult.Failed.class, rig.gateway().invoke(CALL)).effectMayHaveHappened());
            assertEquals(1, rig.calls.get());
            assertTrue(rig.boundaryEvents.contains("agent.tool.failed"));
        }
    }

    @Test void schemaFormatsAreAssertionsRatherThanAnnotations() {
        var rig = new Rig();
        var reg = new GovernedToolGateway.Registration(
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"format\":\"email\"}}}",
                OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, rig.tool);
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var gateway = new GovernedToolGateway(Map.of("search", reg), () -> Optional.of(APPROVAL), profile(Map.of("search", reg)), pep, event -> { }, TEST_BUDGET, CLOCK);
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        assertEquals(0, rig.calls.get());
    }

    @Test void interruptionOfTheCallerIsPreservedForReadOnlyTools() throws Exception {
        var rig = new Rig(); rig.mutating = false;
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
        assertFalse(assertInstanceOf(ToolResult.Failed.class, result.get()).effectMayHaveHappened());
        assertTrue(interrupted.get());
    }

    @Test void timeoutWithholdsLateOutputAndDoesNotAssumeTheToolStopped() throws Exception {
        var rig = new Rig(); rig.mutating = false; rig.timeout = Duration.ofMillis(100);
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
            assertFalse(result.effectMayHaveHappened());
            assertEquals(1, rig.calls.get());
            assertEquals(1, finished.getCount(), "cancellation is not proof that the tool stopped");
            assertFalse(result.reason().contains("private"));
        } finally { release.countDown(); assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
    }

    @Test void stalledTimedOutToolsCannotCreateUnboundedWorkers() throws Exception {
        var rig = new Rig(); rig.mutating = false; rig.timeout = Duration.ofMillis(100);
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
                    () -> new GovernedToolGateway.Registration(INPUT, OUTPUT, false, timeout, IMPLEMENTATION, args -> "{}"));
    }

    @Test void approvalUnavailableOnTheWorkerRefusesBeforeCallingTheTool() {
        var rig = new Rig();
        var registration = new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, rig.tool);
        var caller = Thread.currentThread();
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var gateway = new GovernedToolGateway(Map.of("search", registration),
                () -> Thread.currentThread() == caller ? Optional.of(APPROVAL) : Optional.empty(), profile(Map.of("search", registration)), pep, event -> { }, TEST_BUDGET, CLOCK);
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        assertEquals(0, rig.calls.get());
    }

    @Test void aTimedOutWorkerCannotStartTheToolAfterAStalledApprovalLookup() throws Exception {
        var caller = Thread.currentThread();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var lookupFinished = new java.util.concurrent.CountDownLatch(1);
        var invoked = new java.util.concurrent.CountDownLatch(1);
        var reg = new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofMillis(150), IMPLEMENTATION,
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
        }, profile(Map.of("search", reg)), pep, event -> { }, TEST_BUDGET, CLOCK);
        try {
            assertFalse(assertInstanceOf(ToolResult.Failed.class, gateway.invoke(CALL)).effectMayHaveHappened());
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

    @Test void unavailableBudgetPreventsTheToolAndLostDeadlineWithholdsOutput() {
        var refused = new Rig(); refused.budgetCurrent.set(false);
        assertInstanceOf(ToolResult.Rejected.class, refused.gateway().invoke(CALL));
        assertEquals(0, refused.calls.get());
        var lost = new Rig(); lost.mutating = false;
        lost.tool = args -> { lost.calls.incrementAndGet(); lost.budgetCurrent.set(false); return "{\"result\":\"private\"}"; };
        assertFalse(assertInstanceOf(ToolResult.Failed.class, lost.gateway().invoke(CALL)).effectMayHaveHappened());
        assertEquals(1, lost.admissions.get()); assertEquals(1, lost.calls.get());
    }

    @Test void profileAndRegistryMustMatchBeforeTheToolCanRun() {
        var rig = new Rig();
        var original = new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, rig.tool);
        var altered = new GovernedToolGateway.Registration(INPUT, OUTPUT, true, Duration.ofSeconds(1), IMPLEMENTATION, rig.tool);
        var registry = Map.of("search", original);
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var approved = profile(registry);
        assertThrows(IllegalArgumentException.class, () -> new GovernedToolGateway(Map.of("search", altered),
                () -> Optional.of(APPROVAL), approved, pep, event -> { }, TEST_BUDGET, CLOCK));
        var noTools = new ApprovedToolProfile(approved.id(), approved.version(), approved.configurationFingerprint(),
                AGENT, Set.of(), approved.maxCalls(), approved.maxCallTime());
        var restricted = new GovernedToolGateway(registry, () -> Optional.of(APPROVAL), noTools, pep, event -> { }, TEST_BUDGET, CLOCK);
        assertInstanceOf(ToolResult.Rejected.class, restricted.invoke(CALL));
        var shortTime = new ApprovedToolProfile(approved.id(), approved.version(), approved.configurationFingerprint(),
                AGENT, Set.of("search"), approved.maxCalls(), Duration.ofMillis(100));
        var tooSlow = new GovernedToolGateway(registry, () -> Optional.of(APPROVAL), shortTime, pep, event -> { }, TEST_BUDGET, CLOCK);
        assertInstanceOf(ToolResult.Rejected.class, tooSlow.invoke(CALL));
        assertEquals(0, rig.calls.get());
    }

    @Test void registryFingerprintPinsSchemasTimeoutAndImplementationIdentity() {
        GovernedToolGateway.Tool tool = args -> "{}";
        var first = new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, tool);
        String hash = GovernedToolGateway.configurationFingerprint(Map.of("search", first));
        for (var changed : java.util.List.of(
                new GovernedToolGateway.Registration("{\"type\":\"object\"}", OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, tool),
                new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(2), IMPLEMENTATION, tool),
                new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(1), "b".repeat(64), tool)))
            assertNotEquals(hash, GovernedToolGateway.configurationFingerprint(Map.of("search", changed)));
    }

    @Test void anUnprofiledBudgetCannotAuthorizeTheGateway() {
        var rig = new Rig();
        var registration = new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, rig.tool);
        var registry = Map.of("search", registration);
        var pep = new Pep(request -> new Decision("test", DecisionOutcome.ALLOW, "test", POLICY, NOW),
                event -> { }, Duration.ofSeconds(5), CLOCK);
        var unprofiled = new GovernedToolGateway.ToolBudget() {
            @Override public void admit(GovernedToolGateway.Approval approval) { fail("must not admit"); }
            @Override public void requireCurrent(GovernedToolGateway.Approval approval) { }
        };
        var gateway = new GovernedToolGateway(registry, () -> Optional.of(APPROVAL), profile(registry), pep, event -> { }, unprofiled, CLOCK);
        assertInstanceOf(ToolResult.Rejected.class, gateway.invoke(CALL));
        assertEquals(0, rig.calls.get());
    }
    @Test void mutatingRegistrationCannotBypassControlledEffects() {
        var rig = new Rig(); rig.mutating = true;
        assertInstanceOf(ToolResult.Rejected.class, rig.gateway().invoke(CALL));
        assertEquals(0, rig.calls.get()); assertEquals(0, rig.admissions.get());
    }

    @Test void effectTargetIsBoundIntoTheApprovedRegistryFingerprint() {
        var first = GovernedToolGateway.Registration.effectful(INPUT, OUTPUT, Duration.ofSeconds(1), IMPLEMENTATION,
                "target:first", args -> null);
        var second = GovernedToolGateway.Registration.effectful(INPUT, OUTPUT, Duration.ofSeconds(1), IMPLEMENTATION,
                "target:second", args -> null);
        assertNotEquals(GovernedToolGateway.configurationFingerprint(Map.of("search", first)),
                GovernedToolGateway.configurationFingerprint(Map.of("search", second)));
    }

    @Test void controlledReadOnlyModeIsBoundIntoTheApprovedRegistryFingerprint() {
        GovernedToolGateway.Tool tool = args -> "{}";
        var ordinary = new GovernedToolGateway.Registration(INPUT, OUTPUT, false, Duration.ofSeconds(1), IMPLEMENTATION, tool);
        var controlled = GovernedToolGateway.Registration.controlledReadOnly(INPUT, OUTPUT, Duration.ofSeconds(1), IMPLEMENTATION, tool);
        assertNotEquals(GovernedToolGateway.configurationFingerprint(Map.of("search", ordinary)),
                GovernedToolGateway.configurationFingerprint(Map.of("search", controlled)));
    }

}
