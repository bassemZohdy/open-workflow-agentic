package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Exact system-fact representation only. Real atomic journal/outbox behavior is tested separately against PostgreSQL. */
class EffectAuditProducerTest {
    static final Ulid ID=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    static final Instant AT=Instant.parse("2026-10-20T08:00:00.123456Z");
    static final EffectScope SCOPE=new EffectScope("t",ID,new Subject(SubjectKind.USER,"alice"));
    static final EffectKey KEY=new EffectKey(SCOPE.threadId(),"charge","stable-id");
    static EffectAuditProducer.Fact fact(long id,EffectState from,EffectState to,String reason) {
        return new EffectAuditProducer.Fact(id,AT,KEY,from,to,reason);
    }
    static EffectAuditProducer.Retained build(EffectAuditProducer.Fact fact) {
        return EffectAuditProducer.build(SCOPE,fact,"EFFECT","approved-target","b".repeat(64),ID);
    }
    @Test void fixedDraftAndSourceMatchIndependentPythonCanonicalGoldenWithoutRenamingEffectKey() {
        var result=build(fact(17,null,EffectState.INTENDED,"effect.intended"));
        assertEquals("as1-b023855cac45e7ca0ddc8afc5de026c401b3168e1443b74d56d20374850e8974",result.source().key());
        assertEquals("de438c3d273c9e3f5d224179a5985439a55d0043975c53fc4de87d97e4354151",result.source().contentFingerprint());
        assertEquals(ID,result.draft().id());assertEquals(AT,result.draft().occurredAt());assertEquals(17,result.source().sequence());
        assertEquals("effect.intent",result.draft().action());assertEquals(SCOPE.threadId(),KEY.threadId());
        assertEquals("stable-id",KEY.idempotencyKey());assertEquals("charge",KEY.nodeId());
    }
    @Test void allSupportedJournalFactsMapToFiniteSystemTransitions() {
        var cases=List.of(
                Map.entry(fact(1,null,EffectState.INTENDED,"effect.intended"),AuditSource.Transition.INTENT),
                Map.entry(fact(2,EffectState.INTENDED,EffectState.INTENDED,"effect.dispatch"),AuditSource.Transition.DISPATCH),
                Map.entry(fact(3,EffectState.INTENDED,EffectState.INTENDED,"effect.outcome.unknown"),AuditSource.Transition.UNKNOWN),
                Map.entry(fact(4,EffectState.INTENDED,EffectState.CONFIRMED,"effect.outcome"),AuditSource.Transition.OUTCOME),
                Map.entry(fact(5,EffectState.INTENDED,EffectState.FAILED,"effect.outcome"),AuditSource.Transition.OUTCOME),
                Map.entry(fact(6,EffectState.CONFIRMED,EffectState.CONFIRMED,"effect.compensation.unresolved"),AuditSource.Transition.UNKNOWN),
                Map.entry(fact(7,EffectState.FAILED,EffectState.FAILED,"effect.compensation.unresolved"),AuditSource.Transition.UNKNOWN),
                Map.entry(fact(8,EffectState.CONFIRMED,EffectState.COMPENSATED,"effect.outcome"),AuditSource.Transition.COMPENSATION));
        for(var c:cases)assertEquals(c.getValue(),build(c.getKey()).source().transition());
    }
    @Test void unsupportedReasonAndContradictoryStatesNeverBecomeAnOperatorDecision() {
        for(var f:List.of(fact(1,null,EffectState.CONFIRMED,"effect.outcome"),fact(2,EffectState.CONFIRMED,EffectState.CONFIRMED,"effect.dispatch"),
                fact(3,EffectState.INTENDED,EffectState.INTENDED,"operator says approved")))assertThrows(IllegalStateException.class,()->build(f));
    }
    @Test void payloadFreeSystemDraftRetainsFullOwnerAndHashesOpaqueTargetAndEffectKey() {
        var value=build(fact(17,null,EffectState.INTENDED,"effect.intended"));var draft=value.draft();
        assertEquals(new Subject(SubjectKind.SERVICE,"system:effect-ledger"),draft.actor());
        assertEquals("USER",draft.attributes().get("effect.owner.kind"));assertEquals("alice",draft.attributes().get("effect.owner.id"));
        assertFalse(draft.attributes().containsValue("approved-target"));assertFalse(draft.attributes().containsValue("stable-id"));
        assertEquals(15,draft.attributes().size());
        var other=new EffectScope("t",ID,new Subject(SubjectKind.AGENT,"alice"));
        assertEquals(SCOPE.threadId(),other.threadId()); // Preserve old ws1 identity, FULL owner remains separate.
        var changed=EffectAuditProducer.build(other,fact(17,null,EffectState.INTENDED,"effect.intended"),"EFFECT","approved-target","b".repeat(64),ID);
        assertNotEquals(value.source().scopeFingerprint(),changed.source().scopeFingerprint());assertNotEquals(value.source().key(),changed.source().key());
    }
    @Test void retainedSourceRefusesContentOrAttributionMismatchAndFinerTime() {
        var value=build(fact(17,null,EffectState.INTENDED,"effect.intended"));var s=value.source();
        assertThrows(IllegalStateException.class,()->new EffectAuditProducer.Retained(new AuditSource(1,s.scopeFingerprint(),s.keyFingerprint(),s.transition(),s.sequence(),"f".repeat(64)),value.draft()));
        assertThrows(IllegalStateException.class,()->new EffectAuditProducer.Retained(new AuditSource(1,"f".repeat(64),s.keyFingerprint(),s.transition(),s.sequence(),s.contentFingerprint()),value.draft()));
        var nano=new EffectAuditProducer.Fact(17,AT.plusNanos(1),KEY,null,EffectState.INTENDED,"effect.intended");
        assertThrows(IllegalStateException.class,()->build(nano));
    }
    @Test void defaultLedgerDoesNotEnableScopedWritesOrTouchDatabase() {
        var calls=new AtomicInteger();
        var ds=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            if(m.getName().equals("getConnection")){calls.incrementAndGet();throw new AssertionError("unexpected SQL");}return null;
        });
        var ledger=new JdbcEffectLedger(ds);
        assertThrows(IllegalStateException.class,()->ledger.prepareScoped(SCOPE,SCOPE.command("charge","1","approved-target","{}"),Duration.ZERO,AT));
        assertEquals(0,calls.get());
    }
}
