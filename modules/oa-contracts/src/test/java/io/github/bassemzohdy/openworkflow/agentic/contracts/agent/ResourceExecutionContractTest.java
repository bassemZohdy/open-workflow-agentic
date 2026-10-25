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
    @Test void executionAndOutcomeFingerprintsMatchIndependentPythonVectors() {
        // Independent strict UTF-8 / struct.pack('>i', length), struct.pack('>q', number), hashlib.sha256.
        assertEquals("rc1-82b4f65ae24469b721ff38a2e6118ac882784afa92d75c900b7fbaa6fbe77470",entry().callKey());
        assertEquals("re1-74bd37f5edf459c81a934cedcc63338719956eea40414793a76456c7345d661f",entry().fingerprint());
        assertEquals("1b54f7810ce583c28e331435c5622992bf93d2783ad238a777b469a15dc2327f",outcome().fingerprint());
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
