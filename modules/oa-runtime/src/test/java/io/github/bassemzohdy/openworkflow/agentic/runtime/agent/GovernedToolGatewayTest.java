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
        GovernedToolGateway.Tool tool = args -> { calls.incrementAndGet(); return "{\"result\":\"ok\"}"; };
        DecisionOutcome outcome = DecisionOutcome.ALLOW;
        Runnable duringAudit = () -> { };
        boolean mutating;
        GovernedToolGateway gateway() {
            var registration = new GovernedToolGateway.Registration(INPUT, OUTPUT, mutating, tool);
            var pep = new Pep(request -> { decisions.incrementAndGet(); return new Decision("test", outcome, "test", POLICY, NOW); },
                    event -> { audits.incrementAndGet(); duringAudit.run(); }, Duration.ofSeconds(5), CLOCK);
            return new GovernedToolGateway(Map.of("search", registration), () -> Optional.ofNullable(approval.get()), pep, CLOCK);
        }
    }

    @Test void allowedCallValidatesAndAuditsBeforeOneInvocation() {
        var rig = new Rig();
        var completed = assertInstanceOf(ToolResult.Completed.class, rig.gateway().invoke(CALL));
        assertEquals("{\"result\":\"ok\"}", completed.output());
        assertEquals(1, rig.decisions.get()); assertEquals(1, rig.audits.get()); assertEquals(1, rig.calls.get());
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
                    () -> new GovernedToolGateway.Registration(schema, OUTPUT, false, args -> "{}"));
        }
    }
}
