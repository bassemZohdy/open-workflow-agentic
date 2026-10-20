package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable manual decision DATA, never authorization. A trusted producer must verify the actual operator,
 * oa.recover/resolve-effect policy, current full scope and authoritative evidence before mutation, and mandatory
 * central audit/current authority before private output or advancement. Fingerprints are attribution, not permits.
 * Keep this SAME id and complete tuple on uncertain replies. Only an actual unresolved intent can be decided;
 * compensation is a separate effect, never an arbitrary manual COMPENSATED outcome. No payload/token/reply text.
 */
public record EffectManualDecision(Ulid id,EffectScope scope,EffectCommand command,long expectedJournalId,
        EffectState outcome,Subject operator,PolicyRef policy,String authorizationFingerprint,String evidenceFingerprint) {
    public EffectManualDecision {
        Objects.requireNonNull(id);Objects.requireNonNull(scope);Objects.requireNonNull(command);scope.requireKey(command.key());
        if(expectedJournalId<1)throw new IllegalArgumentException("expected journal id must be positive");
        if(outcome!=EffectState.CONFIRMED && outcome!=EffectState.FAILED)throw new IllegalArgumentException("manual outcome requires confirmation or authoritative refusal");
        Objects.requireNonNull(operator);Objects.requireNonNull(policy);
        requireFingerprint(authorizationFingerprint);requireFingerprint(evidenceFingerprint);
        // Reject malformed UTF-16 now, before any persistence or ambiguous fingerprint can occur.
        for(String text:new String[]{scope.tenant(),scope.owner().id(),command.key().nodeId(),command.key().idempotencyKey(),command.target(),operator.id(),policy.policyId(),policy.policySetVersion()})CanonicalEncoder.strictUtf8(text);
    }
    public String reason(){return outcome==EffectState.CONFIRMED?"operator.evidence.confirmed":"operator.evidence.refused";}
    /** v1 uses length-prefixed STRICT UTF-8 text for ALL fields, including decimal journal id; no locale/rounding. */
    public String fingerprint() {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.effect.manual-decision.v1").text("1").text(id.value())
                .text(scope.tenant()).text(scope.instanceId().value()).text(scope.owner().kind().name()).text(scope.owner().id())
                .text(command.key().threadId().value()).text(command.key().nodeId()).text(command.key().idempotencyKey())
                .text(command.target()).text(command.requestFingerprint()).text(Long.toString(expectedJournalId)).text(outcome.name())
                .text(operator.kind().name()).text(operator.id()).text(policy.policyId()).text(policy.policySetVersion())
                .text(authorizationFingerprint).text(evidenceFingerprint).toBytes());
    }
    /** Bounded non-secret audit attribution. The operational audit actor stays distinct from this operator. */
    public Map<String,String> auditAttributes() {
        return Map.of("manual.id",id.value(),"manual.fingerprint",fingerprint(),"manual.operator.kind",operator.kind().name(),
                "manual.operator.id",operator.id(),"manual.policy.id",policy.policyId(),"manual.policy.version",policy.policySetVersion(),
                "manual.authorization",authorizationFingerprint,"manual.evidence",evidenceFingerprint,
                "manual.expectedJournal",Long.toString(expectedJournalId),"manual.reason",reason());
    }
    private static void requireFingerprint(String value) {
        if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("manual evidence/authorization fingerprint must be SHA256");
    }
}
