package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class EventIngressTest {
    static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    final List<AuditEvent> audit = new ArrayList<>();
    final List<AuthorizationRequest> decisions = new ArrayList<>();
    final InstanceRecord row = InstanceRecord.starting(UlidGenerator.shared().next(), new StartRequest("tenant", ALICE,
            new DefinitionRef("agentic/orders", "1"), Map.of(), "one"), Instant.now());
    final InstanceStore store = (InstanceStore) Proxy.newProxyInstance(InstanceStore.class.getClassLoader(),
            new Class<?>[]{InstanceStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("find")) return args[0].equals("tenant") && args[1].equals(row.id()) ? Optional.of(row) : Optional.empty();
                throw new UnsupportedOperationException();
            });
    static class Inbox extends JdbcEventInbox {
        int calls; boolean committed;
        Inbox() { super((DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> { throw new AssertionError("unit fixture must not access a database"); })); }
        @Override public Received receive(InboxMessage message, Instant until) {
            calls++; boolean created = !committed; committed = true; return new Received(message, until, created);
        }
    }
    static EventIngressSettings settings() {
        return new EventIngressSettings() {
            public Duration retention() { return Duration.ofHours(1); }
            public String policyFolder() { return "policy/events"; }
            public Map<String, Producer> producers() { return Map.of("orders", new Producer() {
                public String tenant() { return "tenant"; } public SubjectKind kind() { return SubjectKind.USER; }
                public String subject() { return "alice"; } public String source() { return "urn:test:orders"; }
                public Set<String> types() { return Set.of("order.ready"); }
                public Set<String> definitions() { return Set.of("agentic/orders"); }
                public Set<String> correlations() { return Set.of("wait-1"); }
            }); }
        };
    }
    static CallerContext caller() { return new CallerContext("tenant", ALICE, Set.of("oa.events.receive"), CallerContext.Provenance.VERIFIED_TOKEN); }
    static StructuredEvent event() { return StructuredEventTest.parse("{" + StructuredEventTest.PREFIX + ",\"data\":{\"ok\":true}}"); }
    EventIngress ingress(Inbox inbox, DecisionOutcome outcome, AuditSink sink) {
        PolicyDecisionPoint pdp = request -> { decisions.add(request); return new Decision("test", outcome, "test", PolicyRef.FAIL_CLOSED, Instant.now()); };
        return new EventIngress(inbox, store, new Pep(pdp, audit::add), sink, settings(), Clock.systemUTC());
    }

    @Test
    void validatesDeploymentSettingsWithoutInitializingNativePolicyForUnusedIngress() {
        for (Duration retention : List.of(Duration.ofHours(1), Duration.ZERO)) {
            EventIngressSettings empty = new EventIngressSettings() {
                public Duration retention() { return retention; }
                public Map<String, Producer> producers() { return Map.of(); }
                public String policyFolder() { throw new AssertionError("startup binding validation must not load policy"); }
            };
            if (retention.isZero()) assertThrows(IllegalArgumentException.class,
                    () -> new EventPolicyProducer().validateBindings(null, empty));
            else new EventPolicyProducer().validateBindings(null, empty);
        }
    }

    @Test
    void authorizesVerifiedIdentityAndAuditsBeforeReceipt() {
        Inbox inbox = new Inbox();
        var ingress = ingress(inbox, DecisionOutcome.ALLOW, e -> {
            if (e.action().equals("event.receive.attempt")) assertEquals(0, inbox.calls);
            audit.add(e);
        });
        assertTrue(ingress.receive(caller(), row.id(), "wait-1", event()).created());
        assertEquals(1, inbox.calls); assertEquals(ALICE, decisions.getFirst().subject());
        assertEquals(ActionKind.DATA_ACCESS, decisions.getFirst().action()); assertEquals(row.id(), decisions.getFirst().taskId());
        assertEquals(Set.of("oa.events.receive"), decisions.getFirst().context().scopes());
        assertEquals(List.of("policy.decision", "event.receive.attempt", "event.received"), audit.stream().map(AuditEvent::action).toList());
    }

    @Test
    void unverifiedWrongRouteDeniedAndUnavailablePolicyNeverWriteReceipt() {
        Inbox inbox = new Inbox(); var ingress = ingress(inbox, DecisionOutcome.ALLOW, audit::add);
        assertEquals(403, assertThrows(ProblemException.class, () -> ingress.receive(new CallerContext("tenant", ALICE), row.id(), "wait-1", event())).getResponse().getStatus());
        assertEquals(403, assertThrows(ProblemException.class, () -> ingress.receive(caller(), row.id(), "another-wait", event())).getResponse().getStatus());
        for (var outcome : List.of(DecisionOutcome.DENY, DecisionOutcome.ERROR)) {
            var denied = ingress(inbox, outcome, audit::add);
            assertEquals(outcome == DecisionOutcome.ERROR ? 503 : 403,
                    assertThrows(ProblemException.class, () -> denied.receive(caller(), row.id(), "wait-1", event())).getResponse().getStatus());
        }
        assertEquals(0, inbox.calls);
    }

    @Test
    void auditFailureBeforeReceiptRefusesAndLostResultAuditLeavesRetryableReceipt() {
        Inbox inbox = new Inbox(); var refused = ingress(inbox, DecisionOutcome.ALLOW, e -> { throw new IllegalStateException("offline"); });
        assertEquals(503, assertThrows(ProblemException.class, () -> refused.receive(caller(), row.id(), "wait-1", event())).getResponse().getStatus());
        assertEquals(0, inbox.calls);
        var lostAck = ingress(inbox, DecisionOutcome.ALLOW, e -> { if (e.action().equals("event.received")) throw new IllegalStateException("offline"); });
        assertEquals(503, assertThrows(ProblemException.class, () -> lostAck.receive(caller(), row.id(), "wait-1", event())).getResponse().getStatus());
        assertTrue(inbox.committed);
        assertFalse(ingress(inbox, DecisionOutcome.ALLOW, audit::add).receive(caller(), row.id(), "wait-1", event()).created());
    }
}
