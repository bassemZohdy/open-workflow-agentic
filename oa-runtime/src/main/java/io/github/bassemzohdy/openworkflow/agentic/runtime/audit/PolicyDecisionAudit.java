package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDecisionRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges the policy library's {@link io.github.bassemzohdy.openworkflow.agentic.runtime.policy.AuditSink} (decisions)
 * to the audit trail's {@link AuditSink} (events), WP-105d. Each decision becomes one event whose action is
 * {@value #ACTION}: the subject is the actor, the resource string names the request, and the version stamps carry the
 * policy id and policy-set version that decided it. The two port interfaces are kept separate on purpose; this class
 * is the only place that maps one to the other.
 *
 * <p>A failure to append throws, and {@link io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep} turns the
 * decision into a refusal, so a decision is never released unrecorded.
 */
@ApplicationScoped
public class PolicyDecisionAudit implements io.github.bassemzohdy.openworkflow.agentic.runtime.policy.AuditSink {

    /** The action of every decision event. */
    public static final String ACTION = "policy.decision";
    /** The resource type of every decision event. */
    public static final String RESOURCE_TYPE = "policy-request";

    private final AuditSink trail;
    private final Clock clock;

    /**
     * The trail is injected by its concrete type: {@code AuditTrail} is the only bean that is also an
     * {@link AuditSink} without ambiguity, because {@code AuditProducers} produces both.
     */
    @Inject
    public PolicyDecisionAudit(AuditTrail trail) {
        this(trail, Clock.systemUTC());
    }

    PolicyDecisionAudit(AuditSink trail, Clock clock) {
        this.trail = Objects.requireNonNull(trail, "trail");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void record(PolicyDecisionRecord record) {
        Objects.requireNonNull(record, "record");
        Decision decision = record.decision();
        Map<String, String> attributes = new LinkedHashMap<>(VersionStamps.ofDecision(decision).toAttributes());
        attributes.put("oa.outcome", decision.outcome().name());
        attributes.put("oa.decided-by", decision.decidedBy());
        attributes.put("oa.recovery-path", String.valueOf(record.recoveryPath()));
        trail.record(AuditDraft.of(UlidGenerator.shared().next(), clock.instant().truncatedTo(ChronoUnit.MICROS),
                record.request().subject(), ACTION, RESOURCE_TYPE, record.request().resource(), attributes));
    }
}
