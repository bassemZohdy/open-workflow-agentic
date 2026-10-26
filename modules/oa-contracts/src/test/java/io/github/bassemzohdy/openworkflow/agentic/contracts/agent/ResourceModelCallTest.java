package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ModelCall;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceModelCallTest {
    static final Instant NOW=Instant.parse("2026-10-20T00:00:00Z");
    static final UUID OWNER=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    static AllocatedResourceTask task() {return new AllocatedResourceTask(ResourceTaskAllocationTest.allocation(),NOW,NOW.plusSeconds(120));}
    static ModelCall model(String prompt,int tokens) {var a=task().allocation();return new ModelCall(a.contract().id(),a.modelProfile().modelId(),prompt,tokens);}
    static ResourceExecutionEntry entry() {return new ResourceExecutionEntry(task().allocation().scope(),1);}
    static ResourceModelCall call() {return ResourceModelCall.of(task(),entry(),OWNER,"graph/plan/iteration/1",model("fictional prompt 𝄞",100));}
    static ModelBudgetAccount account() {
        var a=task().allocation();var s=a.scope();return new ModelBudgetAccount(ModelBudgetAccount.keyFor(a),s.tenant(),s.instanceId(),s.owner(),
                ModelBudgetAccount.AnchorKind.ALLOCATION,a.key(),AgentTaskContractFingerprint.of(a.contract()),a.contract().limits().maxCostMicros(),NOW.plusSeconds(1),task().deadline());
    }
    static ModelCallReservation reservation() {return new ModelCallReservation(call(),task(),account(),1000,9000,9000,1,2,NOW.plusSeconds(2),NOW.plusSeconds(12));}
    @Test void nodeIdentityDoesNotResetForChangedRequestEpochExecutorOrOutputLimit() {
        var first=call();
        for(var other:new ResourceModelCall[]{
                ResourceModelCall.of(task(),entry(),OWNER,first.nodeOccurrence(),model("changed",100)),
                ResourceModelCall.of(task(),entry(),OWNER,first.nodeOccurrence(),model("fictional prompt 𝄞",99)),
                new ResourceModelCall(new ResourceExecutionEntry(entry().scope(),2),OWNER,first.nodeOccurrence(),first.requestFingerprint(),100),
                new ResourceModelCall(entry(),UUID.randomUUID(),first.nodeOccurrence(),first.requestFingerprint(),100)}) {
            assertEquals(first.key(),other.key());assertNotEquals(first.fingerprint(),other.fingerprint());
        }
        assertNotEquals(first.key(),new ResourceModelCall(entry(),OWNER,"graph/draft/iteration/1",first.requestFingerprint(),100).key());
        assertFalse(first.toString().contains("fictional prompt"));
    }
    @Test void factoryBindsActualTaskModelTokensAndExactUnicodePrompt() {
        assertThrows(IllegalArgumentException.class,()->ResourceModelCall.of(task(),entry(),OWNER,"plan",
                new ModelCall(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAX"),model("p",100).modelId(),"p",100)));
        assertThrows(IllegalArgumentException.class,()->ResourceModelCall.of(task(),entry(),OWNER,"plan",
                new ModelCall(model("p",100).taskId(),"another-model","p",100)));
        assertThrows(IllegalArgumentException.class,()->ResourceModelCall.of(task(),entry(),OWNER,"plan",model("p",2049)));
        assertThrows(IllegalArgumentException.class,()->ResourceModelCall.requestFingerprint(model("bad\ud800",100)));
        assertNotEquals(ResourceModelCall.requestFingerprint(model("é",100)),ResourceModelCall.requestFingerprint(model("é",100)));
    }
    @Test void accountRetainsFullScopeAnchorAndOriginalContent() {
        var a=account();assertEquals(ModelBudgetAccount.keyFor(task().allocation()),a.key());
        var changed=new ModelBudgetAccount(a.key(),a.tenant(),a.instanceId(),a.owner(),a.anchorKind(),a.anchorId(),
                "a".repeat(64),a.maxCostMicros()-1,a.createdAt(),a.deadline());
        assertNotEquals(a.fingerprint(),changed.fingerprint());
        assertThrows(IllegalArgumentException.class,()->new ModelBudgetAccount(a.key(),a.tenant(),a.instanceId(),
                new Subject(SubjectKind.SERVICE,a.owner().id()),a.anchorKind(),a.anchorId(),a.sourceContractFingerprint(),a.maxCostMicros(),a.createdAt(),a.deadline()));
    }
    @Test void fullReservationIsAProtectedAllowanceNotAResetRemainingBalance() {
        var r=reservation();assertEquals(1000,r.protectedCallBudgetMicros());assertEquals(9000,r.allocationRemainingAfterMicros());
        assertEquals(call().key(),r.key());assertEquals(64,r.fingerprint().length());
        assertNotEquals(r.fingerprint(),new ModelCallReservation(r.call(),r.task(),r.account(),1000,8000,8000,2,1,
                r.reservedAt(),r.callDeadline()).fingerprint());
    }
    @Test void forgedAmountsCountsAndExtendedOrRoundedTimeAreRejected() {
        var r=reservation();
        assertThrows(IllegalArgumentException.class,()->new ModelCallReservation(r.call(),r.task(),r.account(),1,9000,9000,1,2,r.reservedAt(),r.callDeadline()));
        assertThrows(IllegalArgumentException.class,()->new ModelCallReservation(r.call(),r.task(),r.account(),1000,9999,9000,1,2,r.reservedAt(),r.callDeadline()));
        assertThrows(IllegalArgumentException.class,()->new ModelCallReservation(r.call(),r.task(),r.account(),1000,9000,9000,1,1,r.reservedAt(),r.callDeadline()));
        assertThrows(IllegalArgumentException.class,()->new ModelCallReservation(r.call(),r.task(),r.account(),1000,9000,9000,1,2,r.reservedAt(),r.callDeadline().plusNanos(1000)));
        assertThrows(IllegalArgumentException.class,()->new ModelCallReservation(r.call(),r.task(),r.account(),1000,9000,9000,1,2,r.reservedAt().plusNanos(1),r.callDeadline()));
    }
    @Test void originalHistoricalPastTimestampsRemainDataAndRequireExactPrecision() {
        var a=account();var before=Instant.parse("1969-12-31T23:59:59.999999Z");
        var historic=new ModelBudgetAccount(a.key(),a.tenant(),a.instanceId(),a.owner(),a.anchorKind(),a.anchorId(),
                a.sourceContractFingerprint(),a.maxCostMicros(),before,before.plusSeconds(10));
        assertEquals(64,historic.fingerprint().length());
        assertThrows(IllegalArgumentException.class,()->new ModelBudgetAccount(a.key(),a.tenant(),a.instanceId(),a.owner(),a.anchorKind(),a.anchorId(),
                a.sourceContractFingerprint(),a.maxCostMicros(),before.plusNanos(1),before.plusSeconds(10)));
    }
}
