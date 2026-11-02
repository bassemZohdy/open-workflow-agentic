package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.HumanDecisionWake;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.PolicyDecisionPoint;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.ws.rs.WebApplicationException;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class EventIngressTest {
    static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    final List<AuditEvent> audit = new ArrayList<>();
    final List<AuthorizationRequest> decisions = new ArrayList<>();
    final java.util.concurrent.atomic.AtomicInteger targetReads = new java.util.concurrent.atomic.AtomicInteger();
    final InstanceRecord row = InstanceRecord.starting(UlidGenerator.shared().next(), new StartRequest("tenant", ALICE,
            new DefinitionRef("agentic/orders", "1"), Map.of(), "one"), Instant.now());
    final InstanceStore store = (InstanceStore) Proxy.newProxyInstance(InstanceStore.class.getClassLoader(),
            new Class<?>[]{InstanceStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("find")) {
                    targetReads.incrementAndGet();
                    return args[0].equals("tenant") && args[1].equals(row.id()) ? Optional.of(row) : Optional.empty();
                }
                throw new UnsupportedOperationException();
            });
    static class Inbox extends JdbcEventInbox {
        int calls; boolean committed;
        Inbox() { super((DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> { throw new AssertionError("unit fixture must not access a database"); })); }
        @Override public Received receive(InboxMessage message, Instant until) {
            calls++; boolean created = !committed; committed = true; return new Received(message, until, created);
        }
        @Override public Received receive(InboxMessage message, Instant until, Runnable beforeCommit) {
            beforeCommit.run(); return receive(message, until);
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
    static CallerAuthority caller() {
        var now = Instant.now();
        return CallerAuthority.ofUserToken(new VerifiedToken("issuer", ALICE, Set.of("api"), Set.of("oa.events.receive"),
                "tenant", now, now.plusSeconds(120), "id", List.of()), Clock.systemUTC());
    }
    static StructuredEvent event() { return StructuredEventTest.parse("{" + StructuredEventTest.PREFIX + ",\"data\":{\"ok\":true}}"); }
    static final class MovingClock extends Clock {
        Instant instant = Instant.parse("2030-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
        void advance() { instant = instant.plusSeconds(10); }
    }
    static CallerAuthority expiring(MovingClock clock) {
        return CallerAuthority.ofUserToken(new VerifiedToken("issuer", ALICE, Set.of("api"), Set.of("oa.events.receive"),
                "tenant", clock.instant(), clock.instant().plusSeconds(5), "id", List.of()), clock);
    }
    EventIngress ingress(Inbox inbox, DecisionOutcome outcome, AuditSink sink) {
        PolicyDecisionPoint pdp = request -> { decisions.add(request); return new Decision("test", outcome, "test", PolicyRef.FAIL_CLOSED, Instant.now()); };
        return new EventIngress(inbox, store, new Pep(pdp, audit::add), sink, settings(), Clock.systemUTC());
    }

    @Test void expiresDuringAttemptAuditOrReceiptLockBeforeCommitting() {
        for (boolean duringReceipt : List.of(false, true)) {
            var clock = new MovingClock(); var authority = expiring(clock);
            Inbox inbox = new Inbox() {
                @Override public Received receive(InboxMessage message, Instant until, Runnable beforeCommit) {
                    if (duringReceipt) clock.advance();
                    return super.receive(message, until, beforeCommit);
                }
            };
            PolicyDecisionPoint pdp = request -> new Decision("test", DecisionOutcome.ALLOW, "test", PolicyRef.FAIL_CLOSED, clock.instant());
            var ingress = new EventIngress(inbox, store, new Pep(pdp, e -> {}, Duration.ofSeconds(3), clock), e -> {
                if (!duringReceipt && e.action().equals("event.receive.attempt")) clock.advance();
            }, settings(), clock);
            assertEquals(401, assertThrows(WebApplicationException.class,
                    () -> ingress.receive(authority, row.id(), "wait-1", event())).getResponse().getStatus());
            assertFalse(inbox.committed);
        }
    }

    @Test void expiryAfterCommitWithholdsResultButRetryFindsReceipt() {
        var clock = new MovingClock(); var authority = expiring(clock); var inbox = new Inbox();
        PolicyDecisionPoint pdp = request -> new Decision("test", DecisionOutcome.ALLOW, "test", PolicyRef.FAIL_CLOSED, clock.instant());
        var ingress = new EventIngress(inbox, store, new Pep(pdp, e -> {}, Duration.ofSeconds(3), clock), e -> {
            if (e.action().equals("event.received")) clock.advance();
        }, settings(), clock);
        assertEquals(401, assertThrows(WebApplicationException.class,
                () -> ingress.receive(authority, row.id(), "wait-1", event())).getResponse().getStatus());
        assertTrue(inbox.committed);
        assertFalse(ingress.receive(expiring(clock), row.id(), "wait-1", event()).created());
    }

    @Test void forgedOrDevelopmentContextCannotSubstituteForVerifiedAuthority() {
        var ingress = ingress(new Inbox(), DecisionOutcome.ALLOW, audit::add);
        for (var provenance : List.of(CallerContext.Provenance.VERIFIED_TOKEN, CallerContext.Provenance.DEV_HEADERS)) {
            var forged = CallerAuthority.unverified(new CallerContext("tenant", ALICE, Set.of("oa.events.receive"), provenance), Clock.systemUTC());
            assertThrows(WebApplicationException.class, () -> ingress.receive(forged, row.id(), "wait-1", event()));
        }
        assertEquals(0, targetReads.get());
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
    void internalHumanDecisionSourceCannotBeConfiguredOrReceivedExternally() {
        var original = settings().producers().get("orders");
        EventIngressSettings reserved = new EventIngressSettings() {
            public Duration retention() { return Duration.ofHours(1); }
            public String policyFolder() { return "policy/events"; }
            public Map<String, Producer> producers() { return Map.of("reserved", new Producer() {
                public String tenant() { return original.tenant(); }
                public SubjectKind kind() { return original.kind(); }
                public String subject() { return original.subject(); }
                public String source() { return HumanDecisionWake.SOURCE; }
                public Set<String> types() { return original.types(); }
                public Set<String> definitions() { return original.definitions(); }
                public Set<String> correlations() { return original.correlations(); }
            }); }
        };
        assertThrows(IllegalArgumentException.class, () -> EventIngress.validateSettings(reserved));
        Inbox inbox = new Inbox();
        var ingress = ingress(inbox, DecisionOutcome.ALLOW, audit::add);
        var forged = StructuredEventTest.parse("{" + StructuredEventTest.PREFIX.replace("urn:test:orders", HumanDecisionWake.SOURCE) + ",\"data\":{\"ok\":true}}");
        assertEquals(403, assertThrows(ProblemException.class,
                () -> ingress.receive(caller(), row.id(), "wait-1", forged)).getResponse().getStatus());
        assertEquals(0, inbox.calls);
        assertEquals(0, targetReads.get());
        assertTrue(decisions.isEmpty());
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
        assertEquals(403, assertThrows(ProblemException.class, () -> ingress.receive(CallerAuthority.unverified(new CallerContext("tenant", ALICE), Clock.systemUTC()), row.id(), "wait-1", event())).getResponse().getStatus());
        assertEquals(403, assertThrows(ProblemException.class, () -> ingress.receive(caller(), row.id(), "another-wait", event())).getResponse().getStatus());
        for (var outcome : List.of(DecisionOutcome.DENY, DecisionOutcome.ERROR)) {
            var denied = ingress(inbox, outcome, audit::add);
            assertEquals(outcome == DecisionOutcome.ERROR ? 503 : 403,
                    assertThrows(ProblemException.class, () -> denied.receive(caller(), row.id(), "wait-1", event())).getResponse().getStatus());
        }
        assertEquals(0, inbox.calls);
        assertEquals(0, targetReads.get(), "unverified, unbound or policy-denied callers must not probe targets");
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
