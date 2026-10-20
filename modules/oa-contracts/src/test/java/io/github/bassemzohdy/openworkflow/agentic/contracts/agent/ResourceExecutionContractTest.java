package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceExecutionContractTest {
    static final String MEDIA=ResearchOutputFingerprint.CONTENT_TYPE, PROFILE=ResearchOutputFingerprint.PROFILE, STATE="AAEC/w==";
    static final UUID RUN=UUID.fromString("19b2ca22-659a-4904-b297-c8ddab7b1de7"),
            CHECKPOINT=UUID.fromString("4d70a2e6-6ec8-43ab-b7ea-2304966a385b"), OTHER=UUID.fromString("618920a8-18e2-485a-9df3-056181decb24");
    static ResourceCallScope scope(String target,String request,Subject owner) {
        return new ResourceCallScope("tenant",Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),owner,"app","engine",
                "agentic","research","1.0.0","/do/0/research",7,"fixture-business-coordinate",
                "research-audience","agent:research:run",target,request);
    }
    static ResourceExecutionEntry entry() {
        return new ResourceExecutionEntry(scope("a".repeat(64),"b".repeat(64),new Subject(SubjectKind.USER,"alice")),1);
    }
    static ResourceExecutionOutcome outcome() {
        return new ResourceExecutionOutcome(entry(),PROFILE,RUN,CHECKPOINT,MEDIA,ResearchOutputFingerprint.of(MEDIA,STATE));
    }
    @Test void exactStoredRepresentationMatchesIndependentPythonGolden() {
        // Independent struct '>i' + strict UTF-8 + hashlib; the decoded stream bytes are a DIFFERENT recipe.
        assertEquals("19fb131295937502013de1e7a8ec0ce6848aba209bd492c85f3faadca8c4d81f",ResearchOutputFingerprint.of(MEDIA,STATE));
        assertNotEquals(ResearchOutputFingerprint.of(MEDIA,STATE),ResearchOutputFingerprint.of(MEDIA,"AAEC/g=="));
        assertEquals(STATE,Base64.getEncoder().encodeToString(new byte[]{0,1,2,(byte)255}));
        // The helper does not interpret arbitrary bytes as a valid Java object or a completed graph.
    }
    @Test void unsupportedAndAmbiguousStoredEncodingsRefuseWithoutNormalization() {
        for(var invalid:List.of("","YQ","YQ=","YR==","YQ==\n","____","é===","\uD800"))
            assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of(MEDIA,invalid));
        assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of("application/json",STATE));
        assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of(MEDIA+" ",STATE));
        assertThrows(NullPointerException.class,()->ResearchOutputFingerprint.of(MEDIA,null));
        assertThrows(NullPointerException.class,()->ResearchOutputFingerprint.of(null,STATE));
    }
    @Test void encodedStateIsBoundedBeforeDecodeOrHash() {
        String oversized="A".repeat(ResearchOutputFingerprint.MAX_ENCODED_BYTES+4);
        assertThrows(IllegalArgumentException.class,()->ResearchOutputFingerprint.of(MEDIA,oversized));
    }
    @Test void executionEpochDoesNotBecomeANewCallOrThreadAndTheFullContractStillDiffers() {
        var original=entry();var newEpoch=new ResourceExecutionEntry(original.scope(),2);
        assertEquals(original.callKey(),newEpoch.callKey());assertEquals(original.threadId(),newEpoch.threadId());
        assertNotEquals(original.fingerprint(),newEpoch.fingerprint());
        for(var changed:List.of(scope("c".repeat(64),"b".repeat(64),original.scope().owner()),
                scope("a".repeat(64),"c".repeat(64),original.scope().owner()))) {
            var other=new ResourceExecutionEntry(changed,1);
            assertEquals(original.callKey(),other.callKey());assertNotEquals(original,other);
            assertNotEquals(original.fingerprint(),other.fingerprint());
        }
        var otherKind=new ResourceExecutionEntry(scope("a".repeat(64),"b".repeat(64),new Subject(SubjectKind.AGENT,"alice")),1);
        assertNotEquals(original.callKey(),otherKind.callKey());assertNotEquals(original.threadId(),otherKind.threadId());
        assertThrows(IllegalArgumentException.class,()->new ResourceExecutionEntry(original.scope(),0));
        assertThrows(IllegalArgumentException.class,()->new ResourceExecutionEntry(original.scope(),-1));
        assertThrows(NullPointerException.class,()->new ResourceExecutionEntry(null,1));
    }
    @Test void outcomeChecksFullEntryThreadAndBothPhysicalIdsBeforePrivateDataMatch() {
        var value=outcome();value.requireMatches(entry(),entry().threadId(),RUN,CHECKPOINT,MEDIA,STATE);
        assertThrows(IllegalStateException.class,()->value.requireMatches(new ResourceExecutionEntry(entry().scope(),2),entry().threadId(),RUN,CHECKPOINT,MEDIA,STATE));
        var changed=new ResourceExecutionEntry(scope("f".repeat(64),"b".repeat(64),entry().scope().owner()),1);
        assertEquals(entry().callKey(),changed.callKey());
        assertThrows(IllegalStateException.class,()->value.requireMatches(changed,entry().threadId(),RUN,CHECKPOINT,MEDIA,STATE));
        var otherOwner=new ResourceExecutionEntry(scope("a".repeat(64),"b".repeat(64),new Subject(SubjectKind.USER,"bob")),1);
        assertThrows(IllegalStateException.class,()->value.requireMatches(entry(),otherOwner.threadId(),RUN,CHECKPOINT,MEDIA,STATE));
        assertThrows(IllegalStateException.class,()->value.requireMatches(entry(),entry().threadId(),OTHER,CHECKPOINT,MEDIA,STATE));
        assertThrows(IllegalStateException.class,()->value.requireMatches(entry(),entry().threadId(),RUN,OTHER,MEDIA,STATE));
        assertThrows(IllegalStateException.class,()->value.requireMatches(entry(),entry().threadId(),RUN,CHECKPOINT,"application/json",STATE));
        assertThrows(IllegalStateException.class,()->value.requireMatches(entry(),entry().threadId(),RUN,CHECKPOINT,MEDIA,"AAEC/g=="));
    }
    @Test void outcomeDoesNotAdmitUnknownProfilesOrMalformedDataAndFingerprintsBindPhysicalIds() {
        var value=outcome();assertEquals(value,outcome());assertEquals(value.fingerprint(),outcome().fingerprint());
        var other=new ResourceExecutionOutcome(entry(),PROFILE,OTHER,CHECKPOINT,MEDIA,value.contentFingerprint());
        assertNotEquals(value.fingerprint(),other.fingerprint());
        other=new ResourceExecutionOutcome(entry(),PROFILE,RUN,OTHER,MEDIA,value.contentFingerprint());
        assertNotEquals(value.fingerprint(),other.fingerprint());
        assertThrows(IllegalArgumentException.class,()->new ResourceExecutionOutcome(entry(),"unknown",RUN,CHECKPOINT,MEDIA,value.contentFingerprint()));
        assertThrows(IllegalArgumentException.class,()->new ResourceExecutionOutcome(entry(),PROFILE,RUN,CHECKPOINT,"application/json",value.contentFingerprint()));
        assertThrows(IllegalArgumentException.class,()->new ResourceExecutionOutcome(entry(),PROFILE,RUN,CHECKPOINT,MEDIA,"A".repeat(64)));
        assertThrows(NullPointerException.class,()->new ResourceExecutionOutcome(entry(),PROFILE,null,CHECKPOINT,MEDIA,value.contentFingerprint()));
    }
}
