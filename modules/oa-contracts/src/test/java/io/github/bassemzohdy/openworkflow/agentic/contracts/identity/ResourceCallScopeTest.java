package io.github.bassemzohdy.openworkflow.agentic.contracts.identity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceCallScopeTest {
    static final Ulid INSTANCE=Ulid.parse("01K7VJN9GZVV8M2GXCSX0W9WJ8");
    static final Subject OWNER=new Subject(SubjectKind.USER,"alice");
    static final String REQUEST=ResourceCallScope.requestFingerprint("{\"topic\":\"x\"}");
    static ResourceCallScope scope(String tenant,Ulid instance,Subject owner,String position,int iteration,String occurrence,
                                   String audience,String operation,String request) {
        return new ResourceCallScope(tenant,instance,owner,"application","engine","agentic","research","1.0.0",
                position,iteration,occurrence,audience,operation,"e".repeat(64),request);
    }
    static ResourceCallScope scope() {return scope("tenant",INSTANCE,OWNER,"do/0/research",1,"root/1","agent-resource","agent:research:run",REQUEST);}
    @Test void targetAndPayloadChangesConflictWithTheSameLogicalInvocationInsteadOfCreatingAnotherKey() {
        var first=scope();
        for(var changed:List.of(scope("tenant",INSTANCE,OWNER,"do/0/research",1,"root/1","other","agent:research:run",REQUEST),
                scope("tenant",INSTANCE,OWNER,"do/0/research",1,"root/1","agent-resource","other",REQUEST),
                scope("tenant",INSTANCE,OWNER,"do/0/research",1,"root/1","agent-resource","agent:research:run","f".repeat(64)))) {
            assertEquals(first.key(),changed.key());assertEquals(first.threadId(),changed.threadId());assertNotEquals(first.fingerprint(),changed.fingerprint());
        }
        assertEquals(first,scope());assertEquals(first.fingerprint(),scope().fingerprint());
        var changedTarget=new ResourceCallScope(first.tenant(),first.instanceId(),first.owner(),first.applicationId(),first.engineInstanceId(),
                first.namespace(),first.definition(),first.version(),first.taskPosition(),first.iteration(),first.logicalOccurrence(),
                first.audience(),first.operation(),"d".repeat(64),first.requestFingerprint());
        assertEquals(first.key(),changedTarget.key());assertEquals(first.threadId(),changedTarget.threadId());assertNotEquals(first.fingerprint(),changedTarget.fingerprint());
    }
    @Test void fullOwnerTenantInstanceTaskAndRetainedEntryAttributionSeparateStoredKeys() {
        var different=List.of(scope(),scope("other",INSTANCE,OWNER,"do/0/research",1,"root/1","agent-resource","agent:research:run",REQUEST),
                scope("tenant",Ulid.parse("01K7VJN9GZVV8M2GXCSX0W9WJ9"),OWNER,"do/0/research",1,"root/1","agent-resource","agent:research:run",REQUEST),
                scope("tenant",INSTANCE,new Subject(SubjectKind.SERVICE,"alice"),"do/0/research",1,"root/1","agent-resource","agent:research:run",REQUEST),
                scope("tenant",INSTANCE,new Subject(SubjectKind.USER,"bob"),"do/0/research",1,"root/1","agent-resource","agent:research:run",REQUEST),
                scope("tenant",INSTANCE,OWNER,"do/1/research",1,"root/1","agent-resource","agent:research:run",REQUEST),
                scope("tenant",INSTANCE,OWNER,"do/0/research",2,"root/1","agent-resource","agent:research:run",REQUEST),
                scope("tenant",INSTANCE,OWNER,"do/0/research",1,"root/2","agent-resource","agent:research:run",REQUEST));
        assertEquals(different.size(),new HashSet<>(different.stream().map(ResourceCallScope::key).toList()).size());
        assertEquals(different.size(),new HashSet<>(different.stream().map(ResourceCallScope::threadId).toList()).size());
        assertEquals(different.size()-1,new HashSet<>(different.stream().map(ResourceCallScope::occurrenceKey).toList()).size(),
                "only a new observed counter for the SAME complete business occurrence has the same lookup identity");
    }
    @Test void completeSdkDefinitionIdentitySeparatesCalls() {
        var base=scope();var values=new ArrayList<ResourceCallScope>();values.add(base);
        for(int i=0;i<5;i++) values.add(new ResourceCallScope(base.tenant(),base.instanceId(),base.owner(),i==0?"other":base.applicationId(),
                i==1?"other":base.engineInstanceId(),i==2?"other":base.namespace(),i==3?"other":base.definition(),i==4?"2":base.version(),
                base.taskPosition(),base.iteration(),base.logicalOccurrence(),base.audience(),base.operation(),base.targetFingerprint(),base.requestFingerprint()));
        assertEquals(6,new HashSet<>(values.stream().map(ResourceCallScope::key).toList()).size());
        assertEquals(6,new HashSet<>(values.stream().map(ResourceCallScope::occurrenceKey).toList()).size());
    }
    @Test void occurrenceRecoversOriginalAttributionAcrossEntryCountersButNeverCollapsesBusinessCoordinates() {
        var first=scope();
        var retry=scope("tenant",INSTANCE,OWNER,"do/0/research",2,"root/1","agent-resource","agent:research:run",REQUEST);
        assertNotEquals(first.key(),retry.key());assertEquals(first.occurrenceKey(),retry.occurrenceKey());
        assertTrue(first.occurrenceKey().matches("ro1-[0-9a-f]{64}"));assertNotEquals(first.occurrenceKey(),first.key());
        var next=scope("tenant",INSTANCE,OWNER,"do/0/research",2,"root/2","agent-resource","agent:research:run",REQUEST);
        assertNotEquals(first.occurrenceKey(),next.occurrenceKey());
        var changedRequest=scope("tenant",INSTANCE,OWNER,"do/0/research",2,"root/1","other","other","f".repeat(64));
        assertEquals(first.occurrenceKey(),changedRequest.occurrenceKey(),"changed contracts must conflict at this SAME lookup");
    }
    @Test void maximumSubjectAndDelimiterUnicodePartsAreBoundedAndNeverTruncatedOrNormalized() {
        var longest=scope("tenant",INSTANCE,new Subject(SubjectKind.USER,"u".repeat(256)),"do/0/x",1,"root/1","aud","op",REQUEST);
        assertEquals(ThreadId.LENGTH,longest.threadId().value().length());
        assertNotEquals(longest.threadId(),scope("tenant",INSTANCE,new Subject(SubjectKind.USER,"u".repeat(255)),"do/0/x",1,"root/1","aud","op",REQUEST).threadId());
        assertNotEquals(scope("a|b",INSTANCE,OWNER,"c",1,"é","aud","op",REQUEST).key(),
                scope("a",INSTANCE,OWNER,"b|c",1,"e\u0301","aud","op",REQUEST).key());
        assertNotEquals(longest.threadId(),ThreadId.derive("tenant","u".repeat(256),"direct:"+longest.key()));
    }
    @Test void renewedCredentialsKeepTheSameInvocationAndIssuerIsPartOfCredentialIdentity() {
        var at=Instant.parse("2026-10-20T06:00:00Z");var grant=INSTANCE;
        var a=new ResourceCallBinding("https://issuer-a","jti",grant,scope(),at,at.plusSeconds(300));
        var b=new ResourceCallBinding("https://issuer-b","jti",grant,scope(),at,at.plusSeconds(300));
        var renewed=new ResourceCallBinding("https://issuer-a","new-jti",grant,scope(),at.plusSeconds(60),at.plusSeconds(360));
        assertNotEquals(a.key(),b.key());assertNotEquals(a.key(),renewed.key());assertEquals(a.scope(),renewed.scope());
        assertEquals(a.scope().threadId(),renewed.scope().threadId());assertNotEquals(a.scope().key(),a.key());
        assertThrows(IllegalArgumentException.class,() -> new ResourceCallBinding("issuer","id",grant,scope(),at,at));
        assertThrows(IllegalArgumentException.class,() -> new ResourceCallBinding("issuer","id",grant,scope(),at,at.plusMillis(300001)));
    }
    @Test void malformedMetadataAndOversizedRequestBytesFailBeforeAnyRuntimeDependency() {
        assertThrows(IllegalArgumentException.class,() -> scope("tenant",INSTANCE,OWNER,"do/0/x",0,"root/1","aud","op",REQUEST));
        assertThrows(IllegalArgumentException.class,() -> scope("tenant",INSTANCE,OWNER,"do/0/x",1,"root/1","aud","op","not-a-hash"));
        assertThrows(IllegalArgumentException.class,() -> scope("tenant",INSTANCE,OWNER,"do/0/x",1,"root/1","aud"," ",REQUEST));
        assertThrows(IllegalArgumentException.class,() -> scope("\uD800",INSTANCE,OWNER,"do/0/x",1,"root/1","aud","op",REQUEST));
        assertThrows(IllegalArgumentException.class,() -> ResourceCallScope.requestFingerprint("é".repeat(32769)));
        assertThrows(IllegalArgumentException.class,() -> ResourceCallScope.requestFingerprint("\uD800"));
        assertNotEquals(REQUEST,ResourceCallScope.requestFingerprint("{\"topic\":\"y\"}"));
    }
}
