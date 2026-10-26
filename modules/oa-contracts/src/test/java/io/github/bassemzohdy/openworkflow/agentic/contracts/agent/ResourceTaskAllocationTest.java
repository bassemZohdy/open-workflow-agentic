package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResourceTaskAllocationTest {
    static final Ulid ID=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    static final Ulid CHILD=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW");
    static final Ulid GRANT=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX");
    static final Subject OWNER=new Subject(SubjectKind.USER,"alice");
    static ResourceCallScope scope() {
        return new ResourceCallScope("tenant",ID,OWNER,"app","engine","agentic","research","1","do/0",1,
                "root/foreach/1","research","run","a".repeat(64),"b".repeat(64));
    }
    static AgentTaskContract task() {
        return new AgentTaskContract(CHILD,"resource-request-sha256:"+scope().requestFingerprint(),
                Set.of("tool:z","tool:a"),Set.of("data:z","data:a"),RiskTier.HIGH,
                new ExecutionLimits(10,Duration.ofSeconds(120),10_000),OWNER,GRANT);
    }
    static ApprovedModelProfile profile() {
        return new ApprovedModelProfile("research-profile","1","c".repeat(64),new Subject(SubjectKind.AGENT,"research"),
                "approved-model",3,1000,Duration.ofSeconds(10),2048);
    }
    static ResourceTaskAllocation allocation() { return new ResourceTaskAllocation(scope(),null,task(),profile()); }
    @Test void allocationBindsOriginalCallAndDoesNotReuseLegacyParentThread() {
        var allocation=new ResourceTaskAllocation(scope(),ID,task(),profile());
        assertTrue(allocation.key().matches("ra1-[0-9a-f]{64}"));
        assertEquals(64,allocation.fingerprint().length());
        assertNotEquals(scope().threadId(),ThreadId.derive(scope().tenant(),OWNER.id(),ID.value()));
        assertEquals(task(),allocation.contract());
        assertThrows(IllegalArgumentException.class,()->new ResourceTaskAllocation(scope(),CHILD,task(),profile()));
    }
    @Test void changedApprovalOrParentConflictsAtTheSameAllocationIdentity() {
        var first=allocation();
        var changedTask=new AgentTaskContract(task().id(),"another approved goal",task().permittedTools(),
                task().permittedDataScopes(),task().riskTier(),task().limits(),OWNER,GRANT);
        var changed=new ResourceTaskAllocation(scope(),null,changedTask,profile());
        assertEquals(first.key(),changed.key());assertNotEquals(first.fingerprint(),changed.fingerprint());
        var parent=new ResourceTaskAllocation(scope(),ID,task(),profile());
        assertEquals(first.key(),parent.key());assertNotEquals(first.fingerprint(),parent.fingerprint());
        assertNotEquals(first.fingerprint(),new ResourceTaskAllocation(scope(),null,task(),
                new ApprovedModelProfile("research-profile","2","c".repeat(64),profile().agent(),profile().modelId(),
                        3,1000,Duration.ofSeconds(10),2048)).fingerprint());
    }
    @Test void sameBusinessIdentityCannotHideChangedTargetOrRequestContract() {
        var s=scope();var changed=new ResourceCallScope(s.tenant(),s.instanceId(),s.owner(),s.applicationId(),s.engineInstanceId(),
                s.namespace(),s.definition(),s.version(),s.taskPosition(),s.iteration(),s.logicalOccurrence(),s.audience(),
                s.operation(),"d".repeat(64),s.requestFingerprint());
        var first=allocation();var second=new ResourceTaskAllocation(changed,null,task(),profile());
        assertEquals(first.key(),second.key());assertNotEquals(first.fingerprint(),second.fingerprint());
    }
    @Test void fullOwnerKindAndIdMustMatchTheApprovedContract() {
        var alien=new AgentTaskContract(task().id(),task().goal(),Set.of(),Set.of(),RiskTier.HIGH,task().limits(),
                new Subject(SubjectKind.SERVICE,OWNER.id()),GRANT);
        assertThrows(IllegalArgumentException.class,()->new ResourceTaskAllocation(scope(),null,alien,profile()));
    }
    @Test void taskFingerprintIsSetOrderIndependentAndBindsAllLimitsAndAuthority() {
        var t=task();var reversed=new AgentTaskContract(t.id(),t.goal(),new LinkedHashSet<>(List.of("tool:a","tool:z")),
                new LinkedHashSet<>(List.of("data:z","data:a")),t.riskTier(),t.limits(),OWNER,GRANT);
        assertEquals(AgentTaskContractFingerprint.of(t),AgentTaskContractFingerprint.of(reversed));
        for(var changed:List.of(
                new AgentTaskContract(ID,t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),OWNER,GRANT),
                new AgentTaskContract(t.id(),"changed",t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),OWNER,GRANT),
                new AgentTaskContract(t.id(),t.goal(),Set.of(),t.permittedDataScopes(),t.riskTier(),t.limits(),OWNER,GRANT),
                new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),Set.of(),t.riskTier(),t.limits(),OWNER,GRANT),
                new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),RiskTier.LOW,t.limits(),OWNER,GRANT),
                new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),
                        new ExecutionLimits(9,Duration.ofSeconds(120),10000),OWNER,GRANT),
                new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),
                        new ExecutionLimits(10,Duration.ofSeconds(119),10000),OWNER,GRANT),
                new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),
                        new ExecutionLimits(10,Duration.ofSeconds(120),9999),OWNER,GRANT),
                new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),
                        new Subject(SubjectKind.SERVICE,OWNER.id()),GRANT),
                new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),OWNER,ID)))
            assertNotEquals(AgentTaskContractFingerprint.of(t),AgentTaskContractFingerprint.of(changed));
    }
    @Test void malformedUnicodeAndUnboundedOrMismatchedProfilesAreRejected() {
        var t=task();var malformed=new AgentTaskContract(t.id(),"bad\ud800",Set.of(),Set.of(),RiskTier.HIGH,t.limits(),OWNER,GRANT);
        assertThrows(IllegalArgumentException.class,()->AgentTaskContractFingerprint.of(malformed));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1","c".repeat(64),OWNER,"model",1,1,Duration.ofSeconds(1),1));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1","c".repeat(64),profile().agent(),"model",0,1,Duration.ofSeconds(1),1));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1","c".repeat(64),profile().agent(),"model",1,0,Duration.ofSeconds(1),1));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1","c".repeat(64),profile().agent(),"model",1,1,Duration.ZERO,1));
        assertThrows(IllegalArgumentException.class,()->new ResourceTaskAllocation(scope(),null,t,
                new ApprovedModelProfile("p","1","c".repeat(64),profile().agent(),"model",1,10001,Duration.ofSeconds(1),1)));
        assertThrows(IllegalArgumentException.class,()->new ResourceTaskAllocation(scope(),null,t,
                new ApprovedModelProfile("p","1","c".repeat(64),profile().agent(),"model",1,1,Duration.ofSeconds(121),1)));
    }
    @Test void originalDatabaseLifetimeCannotBeExtendedOrSilentlyRounded() {
        Instant now=Instant.parse("2026-10-20T12:00:00Z");var stored=new AllocatedResourceTask(allocation(),now,now.plusSeconds(90));
        assertEquals(now,stored.createdAt());assertEquals(now.plusSeconds(90),stored.deadline());
        assertThrows(IllegalArgumentException.class,()->new AllocatedResourceTask(allocation(),now,now));
        assertThrows(IllegalArgumentException.class,()->new AllocatedResourceTask(allocation(),now,now.plusSeconds(121)));
        assertThrows(IllegalArgumentException.class,()->new AllocatedResourceTask(allocation(),now.plusNanos(1),now.plusSeconds(1)));
    }
    @Test void everyProfileConfigurationAndLimitChangesTheRetainedContract() {
        var p=profile();
        for(var changed:List.of(
                new ApprovedModelProfile("other",p.version(),p.configurationFingerprint(),p.agent(),p.modelId(),3,1000,p.maxCallTime(),2048),
                new ApprovedModelProfile(p.id(),"2",p.configurationFingerprint(),p.agent(),p.modelId(),3,1000,p.maxCallTime(),2048),
                new ApprovedModelProfile(p.id(),p.version(),"d".repeat(64),p.agent(),p.modelId(),3,1000,p.maxCallTime(),2048),
                new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),new Subject(SubjectKind.AGENT,"other"),p.modelId(),3,1000,p.maxCallTime(),2048),
                new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),p.agent(),"other",3,1000,p.maxCallTime(),2048),
                new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),p.agent(),p.modelId(),4,1000,p.maxCallTime(),2048),
                new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),p.agent(),p.modelId(),3,1001,p.maxCallTime(),2048),
                new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),p.agent(),p.modelId(),3,1000,p.maxCallTime().plusNanos(1),2048),
                new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),p.agent(),p.modelId(),3,1000,p.maxCallTime(),2049))) {
            assertNotEquals(p.fingerprint(),changed.fingerprint());
            var altered=new ResourceTaskAllocation(scope(),null,task(),changed);
            assertEquals(allocation().key(),altered.key());
            assertNotEquals(allocation().fingerprint(),altered.fingerprint());
        }
    }
    @Test void profileBoundsRejectUpperLimitsAndMalformedTextWithoutInventingAggregateBudget() {
        var p=profile();
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("bad\ud800","1",p.configurationFingerprint(),p.agent(),"m",1,1,Duration.ofSeconds(1),1));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1",p.configurationFingerprint(),new Subject(SubjectKind.AGENT,"bad\ud800"),"m",1,1,Duration.ofSeconds(1),1));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1",p.configurationFingerprint(),p.agent(),"m",10001,1,Duration.ofSeconds(1),1));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1",p.configurationFingerprint(),p.agent(),"m",1,1,Duration.ofHours(24).plusNanos(1),1));
        assertThrows(IllegalArgumentException.class,()->new ApprovedModelProfile("p","1",p.configurationFingerprint(),p.agent(),"m",1,1,Duration.ofSeconds(1),32769));
        var bounded=new ApprovedModelProfile("p","1",p.configurationFingerprint(),p.agent(),"m",10000,10000,Duration.ofSeconds(120),32768);
        // Per-call bounds fit; a later durable reservation, not constructor arithmetic, enforces total usage.
        assertDoesNotThrow(()->new ResourceTaskAllocation(scope(),null,task(),bounded));
    }
}
