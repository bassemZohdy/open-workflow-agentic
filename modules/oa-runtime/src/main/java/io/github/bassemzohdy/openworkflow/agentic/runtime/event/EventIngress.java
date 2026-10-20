package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ActionKind;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuthorizationRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyContext;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.problem.ProblemException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditKeys;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Authorize a deployment-bound producer, audit intent, then commit receipt. Never wakes an in-memory listener. */
@ApplicationScoped
public class EventIngress {
    private final JdbcEventInbox inbox;
    private final InstanceStore instances;
    private final Pep pep;
    private final AuditSink audit;
    private final List<Binding> producers;
    private final Duration retention;
    private final Clock clock;
    private final String bindingsHash;
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;

    @Inject
    public EventIngress(JdbcEventInbox inbox, InstanceStore instances, @EventPolicy Pep pep, AuditTrail audit,
                         EventIngressSettings settings) {
        this(inbox, instances, pep, audit, settings, Clock.systemUTC());
    }
    public EventIngress(JdbcEventInbox inbox, InstanceStore instances, Pep pep, AuditSink audit,
                         EventIngressSettings settings, Clock clock) {
        this.inbox = Objects.requireNonNull(inbox); this.instances = Objects.requireNonNull(instances);
        this.pep = Objects.requireNonNull(pep); this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        var config = validated(settings);
        this.retention = config.retention(); this.producers = config.producers(); this.bindingsHash = config.hash();
    }

    /** Startup validation deliberately does not initialize a native policy engine. */
    static void validateSettings(EventIngressSettings settings) { validated(settings); }
    private record Config(Duration retention, List<Binding> producers, String hash) { }
    private static Config validated(EventIngressSettings settings) {
        Duration retention = settings.retention();
        if (retention.isZero() || retention.isNegative() || retention.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("event retention must be positive and at most 30 days");
        }
        if (settings.producers().size() > 256) throw new IllegalArgumentException("too many event producer bindings");
        var producers = settings.producers().values().stream().map(Binding::new).toList();
        for (var p : producers) {
            Checks.requireText("producer tenant", p.tenant(), 256); Objects.requireNonNull(p.kind());
            Checks.requireText("producer subject", p.subject(), 256); Checks.requireText("producer source", p.source(), 1024);
            java.net.URI.create(p.source());
            for (var values : List.of(p.types(), p.definitions(), p.correlations())) {
                if (values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("producer route list must contain 1..64 entries");
                for (String value : values) Checks.requireText("producer route", value, 256);
            }
        }
        var bindingSnapshot = new CanonicalEncoder("oa.event.producers.v1").number(producers.size());
        producers.stream().map(Binding::fingerprint).sorted().forEach(bindingSnapshot::text);
        return new Config(retention, producers, CanonicalEncoder.sha256Hex(bindingSnapshot.toBytes()));
    }

    private record Binding(String tenant, io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind kind,
                           String subject, String source, Set<String> types, Set<String> definitions, Set<String> correlations) {
        Binding(EventIngressSettings.Producer p) {
            this(p.tenant(), p.kind(), p.subject(), p.source(), Set.copyOf(p.types()), Set.copyOf(p.definitions()), Set.copyOf(p.correlations()));
        }
        String fingerprint() {
            var e = new CanonicalEncoder("oa.event.producer.v1").text(tenant).text(kind.name()).text(subject).text(source);
            for (var values : List.of(types, definitions, correlations)) { e.number(values.size()); values.stream().sorted().forEach(e::text); }
            return CanonicalEncoder.sha256Hex(e.toBytes());
        }
    }

    public JdbcEventInbox.Received receive(CallerContext caller, Ulid instance, String correlation, StructuredEvent event) {
        if (caller == null || caller.provenance() == CallerContext.Provenance.UNVERIFIED || caller.subject() == null) {
            throw new ProblemException(403, "verified event caller required");
        }
        InboxMessage message;
        try { message = new InboxMessage(caller.tenant(), event.source(), event.id(), event.type(), instance,
                correlation, event.canonicalEnvelope()); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new ProblemException(400, "invalid event routing or envelope"); }
        String route = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.event.route.v1")
                .text(caller.tenant()).text(event.source()).text(event.type()).text(correlation).toBytes());
        PolicyContext context;
        try { context = new PolicyContext(caller.scopes(), null, null, null, null, null); }
        catch (IllegalArgumentException invalid) { throw new ProblemException(403, "event caller scopes are not acceptable"); }
        var decision = pep.decide(new AuthorizationRequest(caller.subject(), ActionKind.DATA_ACCESS,
                "event:receive:" + route, instance, context));
        if (!decision.allows()) {
            count(decision.outcome() == DecisionOutcome.ERROR ? "unavailable" : "denied");
            throw new ProblemException(decision.outcome() == DecisionOutcome.ERROR ? 503 : 403, "event ingress is not authorized");
        }
        var routes = producers.stream().filter(p -> p.tenant().equals(caller.tenant())
                && p.kind() == caller.subject().kind() && p.subject().equals(caller.subject().id())
                && p.source().equals(event.source()) && p.types().contains(event.type())
                && p.correlations().contains(correlation)).toList();
        if (routes.isEmpty()) {
            record(caller, message, "event.receive.denied", route);
            count("denied");
            throw new ProblemException(403, "event producer is not bound to this source and target route");
        }
        // Unbound producers learn nothing about instance existence. Definition-denied targets look absent too.
        InstanceRecord target;
        try { target = instances.find(caller.tenant(), instance).orElseThrow(() -> new ProblemException(404, "no such instance")); }
        catch (StoreUnavailableException unavailable) { throw new ProblemException(503, "event target lookup is unavailable"); }
        if (routes.stream().noneMatch(p -> p.definitions().contains(target.definition().definitionId()))) {
            record(caller, message, "event.receive.denied", route); count("denied");
            throw new ProblemException(404, "no such instance");
        }
        record(caller, message, "event.receive.attempt", route); // Audit failure refuses before persistence.
        JdbcEventInbox.Received received;
        try { received = inbox.receive(message, clock.instant().plus(retention)); }
        catch (NoSuchElementException missing) { throw new ProblemException(404, "no such instance"); }
        catch (StoreUnavailableException unavailable) { throw new ProblemException(503, "event receipt is unavailable; retry the same event id"); }
        catch (IllegalStateException conflict) { throw new ProblemException(409, "event source/id is bound to different content or routing"); }
        // A failed result audit returns 503. The committed receipt remains idempotently retryable.
        record(caller, message, received.created() ? "event.received" : "event.receive.duplicate", route);
        count(received.created() ? "received" : "duplicate");
        return received;
    }

    private void record(CallerContext caller, InboxMessage message, String action, String route) {
        try {
            audit.record(AuditDraft.of(UlidGenerator.shared().next(), clock.instant(), caller.subject(), action,
                    "event", message.fingerprint(), Map.of(AuditKeys.TENANT, caller.tenant(),
                            AuditKeys.INSTANCE, message.instanceId().value(), "event.route", route, "event.bindings.sha256", bindingsHash)));
        } catch (RuntimeException failure) { count("unavailable"); throw new ProblemException(503, "event audit unavailable; retry the same event id"); }
    }
    private void count(String outcome) { if (metrics != null) metrics.counter("agentic.events.receipts", "outcome", outcome).increment(); }
}
