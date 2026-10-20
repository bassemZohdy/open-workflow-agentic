package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EffectManualDecisionTest {
    static final Ulid ID=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),INSTANCE=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW");
    static final EffectScope SCOPE=new EffectScope("tenant-é",INSTANCE,new Subject(SubjectKind.USER,"alíce-𝄞"));
    static final EffectCommand COMMAND=SCOPE.command("charge","item/é/1","approved-charge","charge\t10");
    static EffectManualDecision decision(){return make(ID,SCOPE,COMMAND,42,EffectState.CONFIRMED,new Subject(SubjectKind.USER,"op-é"),new PolicyRef("resolve-effect","v1"),"a".repeat(64),"b".repeat(64));}
    static EffectManualDecision make(Ulid id,EffectScope scope,EffectCommand command,long expected,EffectState outcome,Subject operator,PolicyRef policy,String authorization,String evidence) {
        return new EffectManualDecision(id,scope,command,expected,outcome,operator,policy,authorization,evidence);
    }
    @Test void originalDecisionIsStableAndMatchesIndependentStrictUtf8Vector() {
        var d=decision();assertEquals(d,decision());assertEquals(d.fingerprint(),decision().fingerprint());
        assertEquals("50a4f0cf8d0f790e1465ef0b9a24e3d912d67408f105509e5578d26b0da0a581",d.fingerprint());
    }
    @Test void fingerprintBindsEveryOperatorPolicyEvidenceAndConcurrencyCoordinate() {
        var d=decision();var versions=List.of(
            make(INSTANCE,SCOPE,COMMAND,42,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,43,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,42,EffectState.FAILED,d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,42,d.outcome(),new Subject(SubjectKind.SERVICE,d.operator().id()),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,42,d.outcome(),new Subject(SubjectKind.USER,"other"),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,42,d.outcome(),d.operator(),new PolicyRef("other","v1"),d.authorizationFingerprint(),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,42,d.outcome(),d.operator(),new PolicyRef("resolve-effect","v2"),d.authorizationFingerprint(),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,42,d.outcome(),d.operator(),d.policy(),"c".repeat(64),d.evidenceFingerprint()),
            make(ID,SCOPE,COMMAND,42,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),"c".repeat(64)));
        for(var changed:versions)assertNotEquals(d.fingerprint(),changed.fingerprint());
        assertEquals(versions.size(),versions.stream().map(EffectManualDecision::fingerprint).distinct().count());
    }
    @Test void fullOwnerKindAndCompleteCommandAreBoundWithoutChangingLegacyEffectKey() {
        var d=decision();var other=new EffectScope(SCOPE.tenant(),SCOPE.instanceId(),new Subject(SubjectKind.SERVICE,SCOPE.owner().id()));
        assertEquals(SCOPE.threadId(),other.threadId());
        assertNotEquals(d.fingerprint(),make(ID,other,COMMAND,42,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()).fingerprint());
        for(var command:List.of(new EffectCommand(COMMAND.key(),"other",COMMAND.requestFingerprint()),new EffectCommand(COMMAND.key(),COMMAND.target(),"c".repeat(64)),SCOPE.command("other","item/é/1","approved-charge","charge\t10")))
            assertNotEquals(d.fingerprint(),make(ID,SCOPE,command,42,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()).fingerprint());
    }
    @Test void intendedCompensatedAndMissingOutcomeCannotBeManualDecisions() {
        var d=decision();for(var outcome:Arrays.asList(EffectState.INTENDED,EffectState.COMPENSATED,null))
            assertThrows(IllegalArgumentException.class,()->make(ID,SCOPE,COMMAND,42,outcome,d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()));
    }
    @Test void expectedJournalAndFullScopeMustIdentifyTheRetainedIntent() {
        var d=decision();for(long expected:List.of(-1L,0L))assertThrows(IllegalArgumentException.class,()->make(ID,SCOPE,COMMAND,expected,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()));
        var other=new EffectScope("other",INSTANCE,SCOPE.owner());
        assertThrows(IllegalArgumentException.class,()->make(ID,other,COMMAND,42,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()));
    }
    @Test void fingerprintsAreMandatoryCanonicalSha256NotEvidencePayloads() {
        var d=decision();for(String bad:Arrays.asList(null,"","credential=secret","A".repeat(64),"a".repeat(63))) {
            assertThrows(IllegalArgumentException.class,()->make(ID,SCOPE,COMMAND,42,d.outcome(),d.operator(),d.policy(),bad,d.evidenceFingerprint()));
            assertThrows(IllegalArgumentException.class,()->make(ID,SCOPE,COMMAND,42,d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),bad));
        }
    }
    @Test void strictUnicodeRefusesMalformedInputAndRetainsNormalizationDifferences() {
        var d=decision();assertThrows(IllegalArgumentException.class,()->make(ID,SCOPE,COMMAND,42,d.outcome(),new Subject(SubjectKind.USER,"bad\ud800"),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint()));
        var first=make(ID,SCOPE,COMMAND,42,d.outcome(),new Subject(SubjectKind.USER,"é"),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint());
        var second=make(ID,SCOPE,COMMAND,42,d.outcome(),new Subject(SubjectKind.USER,"e\u0301"),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint());
        assertNotEquals(first.fingerprint(),second.fingerprint());
    }
    @Test void auditAttributionIsBoundedImmutableAndDistinguishesOperationalActor() {
        var d=decision();var attrs=d.auditAttributes();assertEquals(10,attrs.size());
        assertEquals("op-é",attrs.get("manual.operator.id"));assertEquals("operator.evidence.confirmed",attrs.get("manual.reason"));
        assertThrows(UnsupportedOperationException.class,()->attrs.put("payload","secret"));
        assertTrue(attrs.keySet().stream().allMatch(k->k.startsWith("manual.")));
    }
}
