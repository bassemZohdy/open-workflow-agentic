package io.github.bassemzohdy.openworkflow.agentic.contracts.governance;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuditProjectionContractTest {
    static final Ulid ID=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    static final Instant TIME=Instant.parse("2026-10-20T08:00:00.123456Z");
    static final Subject ACTOR=new Subject(SubjectKind.USER,"alice");
    static final Map<String,String> ATTRS=Map.of("z","é","a","one","oa.chain","effects");
    static AuditEvent draft(Ulid id,Instant time,Subject actor,String action,String type,String resource,Map<String,String> attributes) {
        return new AuditEvent(id,1,time,actor,action,type,resource,attributes,AuditHash.GENESIS,AuditHash.GENESIS);
    }
    static AuditEvent draft() {return draft(ID,TIME,ACTOR,"effect.intent","effect","instance",ATTRS);}
    static AuditSource source(String content) {return new AuditSource(1,"a".repeat(64),"b".repeat(64),AuditSource.Transition.INTENT,17,content);}
    static AuditSource source() {return source(AuditDraftFingerprint.of(draft()));}
    static AuditReceipt receipt() {return new AuditReceipt(source(),ID,"effects",7,"c".repeat(64),source().contentFingerprint());}

    @Test void canonicalBytesHaveAnIndependentUtf8BigEndianGoldenDigest() {
        // Independently encoded with Python struct.pack, UTF-8 and hashlib; not computed by AuditHash/helper.
        assertEquals("02150b5eba88d35d9cb9005f47dd72ae9f9c029153738d775df10dda41720648",AuditDraftFingerprint.of(draft()));
        var order=new LinkedHashMap<String,String>();order.put("z","é");order.put("oa.chain","effects");order.put("a","one");
        assertEquals(AuditDraftFingerprint.of(draft()),AuditDraftFingerprint.of(draft(ID,TIME,ACTOR,"effect.intent","effect","instance",order)));
    }
    @Test void sealingFieldsDoNotChangeDraftContentAndExistingChainFormatStillVerifies() {
        var original=draft();
        var resealed=AuditHash.seal(new AuditEvent(ID,99,TIME,ACTOR,original.action(),original.resourceType(),original.resourceId(),
                ATTRS,"d".repeat(64),"e".repeat(64)));
        assertEquals(AuditDraftFingerprint.of(original),AuditDraftFingerprint.of(resealed));
        assertTrue(AuditHash.verifyChain(List.of(AuditHash.seal(original))).valid());
    }
    @Test void everyRetainedDraftFieldAndAttributeIsBound() {
        var variants=List.of(draft(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"),TIME,ACTOR,"effect.intent","effect","instance",ATTRS),
                draft(ID,TIME.plusNanos(1000),ACTOR,"effect.intent","effect","instance",ATTRS),
                draft(ID,TIME,new Subject(SubjectKind.SERVICE,"alice"),"effect.intent","effect","instance",ATTRS),
                draft(ID,TIME,new Subject(SubjectKind.USER,"bob"),"effect.intent","effect","instance",ATTRS),
                draft(ID,TIME,ACTOR,"effect.dispatch","effect","instance",ATTRS),draft(ID,TIME,ACTOR,"effect.intent","other","instance",ATTRS),
                draft(ID,TIME,ACTOR,"effect.intent","effect","other",ATTRS),
                draft(ID,TIME,ACTOR,"effect.intent","effect","instance",Map.of("z","e\u0301","a","one","oa.chain","effects")),
                draft(ID,TIME,ACTOR,"effect.intent","effect","instance",Map.of("z","é","a","one","oa.chain","other")));
        for(var changed:variants) assertNotEquals(AuditDraftFingerprint.of(draft()),AuditDraftFingerprint.of(changed));
    }
    @Test void implicitAndExplicitDefaultChainAreDifferentImmutableAttributeContent() {
        var implicit=draft(ID,TIME,ACTOR,"effect.intent","effect","instance",Map.of("a","one"));
        var explicit=draft(ID,TIME,ACTOR,"effect.intent","effect","instance",Map.of("a","one","oa.chain","default"));
        assertEquals("default",AuditDraftFingerprint.chainOf(implicit));assertEquals("default",AuditDraftFingerprint.chainOf(explicit));
        assertNotEquals(AuditDraftFingerprint.of(implicit),AuditDraftFingerprint.of(explicit));
    }
    @Test void unsupportedPrecisionBadChainAndMalformedUnicodeAreRefused() {
        assertThrows(IllegalArgumentException.class,()->AuditDraftFingerprint.of(draft(ID,TIME.plusNanos(1),ACTOR,"a","r","id",Map.of())));
        assertThrows(IllegalArgumentException.class,()->AuditDraftFingerprint.of(draft(ID,TIME,ACTOR,"a","r","id",Map.of("oa.chain","invalid/chain"))));
        assertThrows(IllegalArgumentException.class,()->AuditDraftFingerprint.of(draft(ID,TIME,ACTOR,"a","r","id",Map.of("a","\ud800"))));
    }
    @Test void sameSourceWithChangedContentKeepsTheConflictKeyButIsNotEqual() {
        var changed=source("f".repeat(64));assertEquals(source().key(),changed.key());assertNotEquals(source(),changed);
        assertTrue(source().key().matches("as1-[0-9a-f]{64}"));
        var values=List.of(source(),new AuditSource(1,"c".repeat(64),"b".repeat(64),AuditSource.Transition.INTENT,17,"f".repeat(64)),
                new AuditSource(1,"a".repeat(64),"c".repeat(64),AuditSource.Transition.INTENT,17,"f".repeat(64)),
                new AuditSource(1,"a".repeat(64),"b".repeat(64),AuditSource.Transition.OUTCOME,17,"f".repeat(64)),
                new AuditSource(1,"a".repeat(64),"b".repeat(64),AuditSource.Transition.INTENT,18,"f".repeat(64)));
        assertEquals(values.size(),new HashSet<>(values.stream().map(AuditSource::key).toList()).size());
    }
    @Test void sourceSchemaSequenceAndDigestsAreStrict() {
        assertThrows(IllegalArgumentException.class,()->new AuditSource(2,"a".repeat(64),"b".repeat(64),AuditSource.Transition.INTENT,1,"c".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new AuditSource(1,"a".repeat(64),"b".repeat(64),AuditSource.Transition.INTENT,0,"c".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->source("C".repeat(64)));
    }
    @Test void receiptMatchesOnlyTheExactRetainedSourceAndDraft() {
        receipt().requireMatches(source(),draft());
        assertThrows(IllegalStateException.class,()->receipt().requireMatches(source("d".repeat(64)),draft()));
        assertThrows(IllegalStateException.class,()->receipt().requireMatches(source(),draft(ID,TIME,ACTOR,"effect.outcome","effect","instance",ATTRS)));
        var otherEvent=new AuditReceipt(source(),Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"),"effects",7,"c".repeat(64),source().contentFingerprint());
        assertThrows(IllegalStateException.class,()->otherEvent.requireMatches(source(),draft()));
        var otherChain=new AuditReceipt(source(),ID,"other",7,"c".repeat(64),source().contentFingerprint());
        assertThrows(IllegalStateException.class,()->otherChain.requireMatches(source(),draft()));
    }
    @Test void receiptCannotCarryContradictoryContentOrInvalidChainPosition() {
        assertThrows(IllegalArgumentException.class,()->new AuditReceipt(source(),ID,"effects",7,"c".repeat(64),"d".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new AuditReceipt(source(),ID,"effects",0,"c".repeat(64),source().contentFingerprint()));
        assertThrows(IllegalArgumentException.class,()->new AuditReceipt(source(),ID,"bad/chain",7,"c".repeat(64),source().contentFingerprint()));
    }
}
