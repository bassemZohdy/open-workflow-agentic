package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** SDK predicate selection is DATA only: none of these cases grants callback permission or proves T13. */
class SdkListenMatchSetTest {
    private static final String HASH="a".repeat(64);
    private static InboxListenGroup group(InboxListenGroup.Strategy strategy,List<String> filters,
                                          InboxListenGroup.Stop stop,List<String> stopFilters) {
        var task=new InboxTask("tenant",UlidGenerator.shared().next(),"app","engine","ns","name","1","do/0",1);
        return new InboxListenGroup(task,strategy,filters,stop,stopFilters,null,HASH);
    }
    private static InboxMessage receipt(InboxListenGroup group) {
        return new InboxMessage("tenant","urn:test:source","id-1","order.ready",group.task().instanceId(),"route",
                "{\"specversion\":\"1.0\",\"source\":\"urn:test:source\",\"id\":\"id-1\",\"type\":\"order.ready\",\"data\":{\"value\":7}}");
    }
    private static TypeEventRegistration registration(String type,CloudEventPredicate predicate,List<CloudEventPredicate> correlations,
                                                       WorkflowContext workflow,TaskContext task,AtomicInteger callbacks) {
        return new TypeEventRegistration(type,event->callbacks.incrementAndGet(),predicate,correlations,workflow,task);
    }
    private static SdkListenMatchSet.Binding bound(InboxListenGroup.Slot slot,TypeEventRegistration registration) {
        return new SdkListenMatchSet.Binding(slot,registration);
    }
    @Test void duplicateAllFiltersYieldBothSlotsWithoutInvokingCallbacks() {
        var group=group(InboxListenGroup.Strategy.ALL,List.of(HASH,HASH),InboxListenGroup.Stop.NONE,List.of());
        var slots=group.slots(InboxListenGroup.Role.DATA);var callbacks=new AtomicInteger();
        var registration=registration("order.ready",(event,w,t)->true,List.of((event,w,t)->true),null,null,callbacks);
        var selector=new SdkListenMatchSet(group,List.of(bound(slots.get(0),registration),bound(slots.get(1),registration)));
        var matches=selector.matches(receipt(group));
        assertEquals(Set.copyOf(slots),matches);assertEquals(slots,group.classify(matches).slots());assertEquals(0,callbacks.get());
        assertThrows(UnsupportedOperationException.class,()->matches.clear());
    }
    @Test void completeAnyAndUntilMatchesAreRetainedBeforeClassification() {
        var group=group(InboxListenGroup.Strategy.ANY,List.of(HASH,HASH),InboxListenGroup.Stop.ONE,List.of(HASH));
        var data=group.slots(InboxListenGroup.Role.DATA);var until=group.slots(InboxListenGroup.Role.UNTIL);var callbacks=new AtomicInteger();
        var yes=registration("order.ready",(event,w,t)->true,List.of(),null,null,callbacks);
        var selector=new SdkListenMatchSet(group,List.of(bound(data.get(0),yes),bound(data.get(1),yes),bound(until.get(0),yes)));
        var matches=selector.matches(receipt(group));assertEquals(Set.of(data.get(0),data.get(1),until.get(0)),matches);
        assertEquals(InboxListenGroup.Disposition.UNTIL,group.classify(matches).disposition());
        assertEquals(List.of(until.get(0)),group.classify(matches).slots());assertEquals(0,callbacks.get());
    }
    @Test void typeAndCorrelationFiltersDoNotProduceAMatch() {
        var group=group(InboxListenGroup.Strategy.ALL,List.of(HASH,HASH),InboxListenGroup.Stop.NONE,List.of());
        var slots=group.slots(InboxListenGroup.Role.DATA);var callbacks=new AtomicInteger();var predicates=new AtomicInteger();
        var wrongType=registration("another.type",(event,w,t)->{predicates.incrementAndGet();return true;},List.of(),null,null,callbacks);
        var wrongCorrelation=registration("order.ready",(event,w,t)->true,List.of((event,w,t)->false),null,null,callbacks);
        var matches=new SdkListenMatchSet(group,List.of(bound(slots.get(0),wrongType),bound(slots.get(1),wrongCorrelation))).matches(receipt(group));
        assertTrue(matches.isEmpty());assertEquals(0,predicates.get());assertEquals(0,callbacks.get());
    }
    @Test void wildcardCanBindMultiplePhysicalTypesToOneLogicalSlot() {
        var group=group(InboxListenGroup.Strategy.ANY,List.of(),InboxListenGroup.Stop.NONE,List.of());
        var slot=group.slots(InboxListenGroup.Role.DATA).getFirst();var callbacks=new AtomicInteger();
        var a=registration("another.type",(event,w,t)->true,List.of(),null,null,callbacks);
        var b=registration("order.ready",(event,w,t)->true,List.of(),null,null,callbacks);
        assertEquals(Set.of(slot),new SdkListenMatchSet(group,List.of(bound(slot,a),bound(slot,b))).matches(receipt(group)));
        assertEquals(0,callbacks.get());
    }
    @Test void incompleteChangedOrWrongScopeRegistrationsRefuseBeforeAnyMatchIsReturned() {
        var group=group(InboxListenGroup.Strategy.ALL,List.of(HASH,HASH),InboxListenGroup.Stop.NONE,List.of());
        var slots=group.slots(InboxListenGroup.Role.DATA);var calls=new AtomicInteger();
        var yes=registration("order.ready",(event,w,t)->{calls.incrementAndGet();return true;},List.of(),null,null,new AtomicInteger());
        assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(group,List.of(bound(slots.getFirst(),yes))));
        assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(group,List.of(bound(slots.getFirst(),yes),bound(slots.getFirst(),yes))));
        var selector=new SdkListenMatchSet(group,List.of(bound(slots.getFirst(),yes),bound(slots.getLast(),yes)));
        var stored=receipt(group);var wrong=new InboxMessage("other",stored.source(),stored.eventId(),stored.type(),stored.instanceId(),stored.correlationKey(),stored.dataJson());
        assertThrows(IllegalArgumentException.class,()->selector.matches(wrong));assertEquals(0,calls.get());
        var malformed=new InboxMessage(stored.tenant(),stored.source(),stored.eventId(),stored.type(),stored.instanceId(),stored.correlationKey(),"{}");
        assertThrows(IllegalStateException.class,()->selector.matches(malformed));assertEquals(0,calls.get());
    }
    @Test void predicateFailureDoesNotReturnPartialMatchesOrInvokeCallbacks() {
        var group=group(InboxListenGroup.Strategy.ALL,List.of(HASH,HASH),InboxListenGroup.Stop.NONE,List.of());
        var slots=group.slots(InboxListenGroup.Role.DATA);var callbacks=new AtomicInteger();
        var yes=registration("order.ready",(event,w,t)->true,List.of(),null,null,callbacks);
        var fail=registration("order.ready",(event,w,t)->{throw new IllegalStateException("predicate unavailable");},List.of(),null,null,callbacks);
        var selector=new SdkListenMatchSet(group,List.of(bound(slots.getFirst(),yes),bound(slots.getLast(),fail)));
        assertEquals("predicate unavailable",assertThrows(IllegalStateException.class,()->selector.matches(receipt(group))).getMessage());
        assertEquals(0,callbacks.get());
    }
    @Test void modelAwareCorrelationUsesSdkModelRatherThanCloudEventOverload() throws Exception {
        try(var captured=new ListenGroupSnapshotsTest.Captured(ListenGroupSnapshotsTest.ONE)) {
            var group=group(InboxListenGroup.Strategy.ONE,List.of(HASH),InboxListenGroup.Stop.NONE,List.of());
            var slot=group.slots(InboxListenGroup.Role.DATA).getFirst();var calls=new AtomicInteger();var callbacks=new AtomicInteger();
            var modelAware=new ModelAwareCloudEventPredicate() {
                public boolean test(CloudEvent event,WorkflowContext workflow,TaskContext task) {throw new AssertionError("wrong predicate overload");}
                public boolean test(WorkflowModel model,WorkflowContext workflow,TaskContext task) {assertNotNull(model);calls.incrementAndGet();return true;}
            };
            var actual=registration("order.ready",(event,w,t)->true,List.of(modelAware),captured.context.workflow(),captured.context.task(),callbacks);
            assertEquals(Set.of(slot),new SdkListenMatchSet(group,List.of(bound(slot,actual))).matches(receipt(group)));
            assertEquals(1,calls.get());assertEquals(0,callbacks.get());
        }
    }
}
