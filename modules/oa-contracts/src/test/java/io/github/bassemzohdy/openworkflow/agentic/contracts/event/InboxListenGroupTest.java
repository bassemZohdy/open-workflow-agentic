package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.util.*;
import org.junit.jupiter.api.Test;
import static io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxListenGroup.*;

class InboxListenGroupTest {
    private static final String A="a".repeat(64),B="b".repeat(64),BINDING="c".repeat(64);
    private static InboxTask task(int iteration) {return new InboxTask("tenant",Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
            "app","engine","ns","flow","1.0.0","/do/0/awaitEvents",iteration);}
    private static InboxListenGroup group(Strategy strategy,List<String> data,Stop stop,List<String> until,String expression) {
        return new InboxListenGroup(task(1),strategy,data,stop,until,expression,BINDING);
    }
    @Test void duplicateAllFiltersRetainDistinctStableMembershipsOfOneReceipt() {
        var data=new ArrayList<>(List.of(A,A));var group=group(Strategy.ALL,data,Stop.NONE,List.of(),null);
        var slots=group.slots(Role.DATA);assertEquals(2,slots.size());assertNotEquals(slots.get(0).key(),slots.get(1).key());
        assertEquals(slots.get(0).filterFingerprint(),slots.get(1).filterFingerprint());
        assertEquals(slots,group.classify(Set.copyOf(slots)).slots());
        String identity=group.digest();data.clear();assertEquals(identity,group.digest());
        assertEquals(identity,group(Strategy.ALL,List.of(A,A),Stop.NONE,List.of(),null).digest());
        assertNotEquals(identity,group(Strategy.ALL,List.of(A),Stop.NONE,List.of(),null).digest());
    }
    @Test void anyHasOneDeterministicDataMembershipAndUntilPrecedesOverlappingData() {
        var group=group(Strategy.ANY,List.of(A,A),Stop.ONE,List.of(A),null);
        var data=group.slots(Role.DATA);var stop=group.slots(Role.UNTIL).getFirst();
        assertNotEquals(data.getFirst().key(),stop.key());
        assertEquals(List.of(data.getFirst()),group.classify(Set.copyOf(data)).slots());
        var both=new HashSet<>(data);both.add(stop);
        assertEquals(new Match(Disposition.UNTIL,List.of(stop)),group.classify(both));
        assertEquals(new Match(Disposition.NONE,List.of()),group.classify(Set.of()));
    }
    @Test void wildcardAnyAndWildcardUntilAreSeparateLogicalRolesNotEmptySubscriptions() {
        var group=group(Strategy.ANY,List.of(),Stop.ANY,List.of(),null);
        var data=group.slots(Role.DATA).getFirst();var stop=group.slots(Role.UNTIL).getFirst();
        assertEquals(data.filterFingerprint(),stop.filterFingerprint());assertNotEquals(data.key(),stop.key());
        assertEquals(Disposition.UNTIL,group.classify(Set.of(data,stop)).disposition());
        var expression=group(Strategy.ANY,List.of(),Stop.EXPRESSION,List.of(),A);
        assertEquals(1,expression.slots(Role.DATA).size());assertTrue(expression.slots(Role.UNTIL).isEmpty());
        assertEquals(Disposition.DATA,expression.classify(Set.copyOf(expression.slots(Role.DATA))).disposition());
    }
    @Test void overlappingUntilAllMembershipIsNotCollapsedOrMistakenForResultData() {
        var group=group(Strategy.ANY,List.of(A),Stop.ALL,List.of(A,A),null);
        var stop=group.slots(Role.UNTIL);var data=group.slots(Role.DATA).getFirst();
        assertEquals(List.of(stop.getFirst()),group.classify(Set.of(data,stop.getFirst())).slots());
        assertEquals(new Match(Disposition.UNTIL,stop),group.classify(Set.of(data,stop.get(0),stop.get(1))));
        var one=group(Strategy.ONE,List.of(A),Stop.NONE,List.of(),null);
        assertEquals(new Match(Disposition.DATA,one.slots(Role.DATA)),one.classify(Set.copyOf(one.slots(Role.DATA))));
    }
    @Test void orderedDefinitionBindingAndTaskChangesCannotShareReplayIdentity() {
        var first=group(Strategy.ANY,List.of(A,B),Stop.EXPRESSION,List.of(),A);
        assertNotEquals(first.digest(),group(Strategy.ANY,List.of(B,A),Stop.EXPRESSION,List.of(),A).digest());
        assertNotEquals(first.digest(),group(Strategy.ANY,List.of(A,B),Stop.EXPRESSION,List.of(),B).digest());
        assertNotEquals(first.digest(),new InboxListenGroup(task(2),first.strategy(),first.dataFilters(),first.stop(),first.stopFilters(),A,BINDING).digest());
        assertNotEquals(first.digest(),new InboxListenGroup(task(1),first.strategy(),first.dataFilters(),first.stop(),first.stopFilters(),A,B).digest());
    }
    @Test void foreignGroupAndForgedOrdinalOrFilterNeverBecomeMatches() {
        var group=group(Strategy.ALL,List.of(A,B),Stop.NONE,List.of(),null);var slot=group.slots(Role.DATA).getFirst();
        var foreign=group(Strategy.ALL,List.of(B,A),Stop.NONE,List.of(),null).slots(Role.DATA).getFirst();
        for(var bad:List.of(foreign,new Slot(slot.groupDigest(),Role.DATA,2,A),new Slot(slot.groupDigest(),Role.DATA,0,B),
                new Slot(slot.groupDigest(),Role.UNTIL,0,A)))
            assertThrows(IllegalArgumentException.class,()->group.classify(Set.of(bad)));
        assertEquals(List.of(slot),group.classify(Set.of(new Slot(slot.groupDigest(),slot.role(),slot.ordinal(),slot.filterFingerprint()))).slots());
    }
    @Test void invalidShapeAndOversizedLogicalGroupsAreRefusedBeforeAnyRuntimeUse() {
        assertThrows(IllegalArgumentException.class,()->group(Strategy.ALL,List.of(),Stop.NONE,List.of(),null));
        assertThrows(IllegalArgumentException.class,()->group(Strategy.ONE,List.of(A,B),Stop.NONE,List.of(),null));
        assertThrows(IllegalArgumentException.class,()->group(Strategy.ALL,List.of(A),Stop.ONE,List.of(B),null));
        assertThrows(NullPointerException.class,()->group(Strategy.ANY,List.of(A),Stop.EXPRESSION,List.of(),null));
        assertThrows(IllegalArgumentException.class,()->group(Strategy.ANY,List.of(A),Stop.NONE,List.of(B),null));
        assertThrows(IllegalArgumentException.class,()->group(Strategy.ANY,List.of(A),Stop.ALL,List.of(),null));
        assertThrows(IllegalArgumentException.class,()->group(Strategy.ANY,List.of("bad"),Stop.NONE,List.of(),null));
        var bound=Collections.nCopies(MAX_SLOTS,A);
        assertEquals(MAX_SLOTS,group(Strategy.ALL,bound,Stop.NONE,List.of(),null).slots(Role.DATA).size());
        assertThrows(IllegalArgumentException.class,()->group(Strategy.ANY,bound,Stop.ONE,List.of(B),null));
    }
    @Test void selectionCannotMixGroupsRolesDuplicateSlotsOrUnorderedMembership() {
        var group=group(Strategy.ANY,List.of(A,B),Stop.ONE,List.of(A),null);
        var data=group.slots(Role.DATA);var stop=group.slots(Role.UNTIL).getFirst();
        assertThrows(IllegalArgumentException.class,()->new Match(Disposition.NONE,List.of(data.getFirst())));
        assertThrows(IllegalArgumentException.class,()->new Match(Disposition.DATA,List.of()));
        assertThrows(IllegalArgumentException.class,()->new Match(Disposition.DATA,List.of(stop)));
        assertThrows(IllegalArgumentException.class,()->new Match(Disposition.DATA,List.of(data.getFirst(),data.getFirst())));
        assertThrows(IllegalArgumentException.class,()->new Match(Disposition.DATA,List.of(data.get(1),data.getFirst())));
        var other=group(Strategy.ANY,List.of(B,A),Stop.ONE,List.of(A),null).slots(Role.DATA).get(1);
        assertThrows(IllegalArgumentException.class,()->new Match(Disposition.DATA,List.of(data.getFirst(),other)));
    }
    @Test void snapshotFingerprintPinsExactBytesAndRejectsMalformedOrOversizedUtf8() {
        assertEquals(InboxListenGroup.snapshotFingerprint("{\"x\":1}"),InboxListenGroup.snapshotFingerprint("{\"x\":1}"));
        assertNotEquals(InboxListenGroup.snapshotFingerprint("{\"x\":1}"),InboxListenGroup.snapshotFingerprint("{ \"x\":1 }"));
        assertNotEquals(InboxListenGroup.snapshotFingerprint("{\"x\":\"é\"}"),InboxListenGroup.snapshotFingerprint("{\"x\":\"e\u0301\"}"));
        assertThrows(IllegalArgumentException.class,()->InboxListenGroup.snapshotFingerprint("\uD800"));
        assertThrows(IllegalArgumentException.class,()->InboxListenGroup.snapshotFingerprint("😀".repeat(65537)));
    }
}
