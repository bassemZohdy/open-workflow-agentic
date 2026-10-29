package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.serverlessworkflow.api.types.ListenTask;
import io.serverlessworkflow.impl.events.InMemoryEvents;
import org.junit.jupiter.api.Test;

/** The literal-only subset is data preparation; no store, delivery or T13 claim. */
class SdkListenMatchSetTest {
    private static InboxMessage receipt(InboxListenGroup group,String type) {
        return new InboxMessage(group.task().tenant(),"urn:test:source","id-1",type,group.task().instanceId(),"route",
                "{\"specversion\":\"1.0\",\"source\":\"urn:test:source\",\"id\":\"id-1\",\"type\":\""+type+"\"}");
    }
    @Test void duplicateFiltersAreDerivedFromTheActualPinnedTask() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                all:
                  - with: { type: org.openworkflow.agentic.group.a }
                  - with: { type: org.openworkflow.agentic.group.a }
            """)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var matches=new SdkListenMatchSet(group,pinned.snapshot()).matches(receipt(group,ListenGroupSnapshotsTest.TYPE)).slots();
            assertEquals(Set.copyOf(group.slots(InboxListenGroup.Role.DATA)),matches);
            assertThrows(UnsupportedOperationException.class,matches::clear);
            assertTrue(new SdkListenMatchSet(group,pinned.snapshot()).matches(receipt(group,"different.type")).slots().isEmpty());
        }
    }
    @Test void bothDataAndUntilAreReturnedBeforeClassification() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                any:
                  - with: { type: org.openworkflow.agentic.group.a }
                until:
                  one:
                    with: { type: org.openworkflow.agentic.group.a }
            """)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var matches=new SdkListenMatchSet(group,pinned.snapshot()).matches(receipt(group,ListenGroupSnapshotsTest.TYPE)).slots();
            assertEquals(2,matches.size());assertEquals(InboxListenGroup.Disposition.UNTIL,group.classify(matches).disposition());
        }
    }
    @Test void wildcardMatchesAndChangedSnapshotIsRefused() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                any: []
            """)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            assertEquals(Set.copyOf(group.slots(InboxListenGroup.Role.DATA)),new SdkListenMatchSet(group,pinned.snapshot()).matches(receipt(group,"arbitrary")).slots());
            assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(group,pinned.snapshot()+" "));
            var changed=new InboxListenGroup(group.task(),InboxListenGroup.Strategy.ANY,List.of("a".repeat(64)),group.stop(),group.stopFilters(),group.expressionFingerprint(),group.configurationFingerprint());
            assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(changed,pinned.snapshot()));
        }
    }
    @Test void foreignReceiptIsRefused() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                one:
                  with: { type: org.openworkflow.agentic.group.a }
            """)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var stored=receipt(pinned.group(),ListenGroupSnapshotsTest.TYPE);
            var other=new InboxMessage("other",stored.source(),stored.eventId(),stored.type(),stored.instanceId(),stored.correlationKey(),stored.dataJson());
            assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(pinned.group(),pinned.snapshot()).matches(other));
        }
    }
    @Test void selectionIsBoundToGroupAndReceipt() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                one:
                  with: { type: org.openworkflow.agentic.group.a }
            """)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var original=receipt(group,ListenGroupSnapshotsTest.TYPE);var selection=new SdkListenMatchSet(group,pinned.snapshot()).matches(original);
            selection.require(group,original);
            var other=new InboxMessage(original.tenant(),original.source(),"different",original.type(),original.instanceId(),original.correlationKey(),original.dataJson());
            assertThrows(IllegalArgumentException.class,()->selection.require(group,other));
            var foreign=new InboxListenGroup(group.task(),InboxListenGroup.Strategy.ANY,group.dataFilters(),group.stop(),group.stopFilters(),group.expressionFingerprint(),group.configurationFingerprint());
            assertThrows(IllegalArgumentException.class,()->selection.require(foreign,original));
        }
    }
    @Test void literalMatchesAgreeWithActualInMemoryConsumer() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                one:
                  with: { type: org.openworkflow.agentic.group.a, id: id-1 }
            """);var events=new InMemoryEvents()) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var filter=((ListenTask)run.context.task().task()).getListen().getTo().getOneEventConsumptionStrategy().getOne();
            var builder=events.listen(filter,run.app);var calls=new AtomicInteger();
            events.register(builder,event->calls.incrementAndGet(),run.context.workflow(),run.context.task());
            var matching=receipt(group,ListenGroupSnapshotsTest.TYPE);var event=StoredCloudEventDecoder.decode(matching);
            events.publish(event).get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertFalse(new SdkListenMatchSet(group,pinned.snapshot()).matches(matching).slots().isEmpty());
            var different=new InboxMessage(group.task().tenant(),"urn:test:source","id-2",ListenGroupSnapshotsTest.TYPE,group.task().instanceId(),"route",
                    "{\"specversion\":\"1.0\",\"source\":\"urn:test:source\",\"id\":\"id-2\",\"type\":\""+ListenGroupSnapshotsTest.TYPE+"\"}");
            events.publish(StoredCloudEventDecoder.decode(different)).get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertTrue(new SdkListenMatchSet(group,pinned.snapshot()).matches(different).slots().isEmpty());
        }
    }
    @Test void untypedFilteredBroadcastIsRefused() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                one:
                  with: { id: id-1 }
            """,false)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());
            assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(pinned.group(),pinned.snapshot()));
        }
    }
    @Test void wildcardMatchesActualSdkBroadcast() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                any: []
            """);var events=new InMemoryEvents()) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var calls=new AtomicInteger();var builder=events.listenToAll(run.app).iterator().next();
            events.register(builder,event->calls.incrementAndGet(),run.context.workflow(),run.context.task());
            var message=receipt(group,"arbitrary.type");events.publish(StoredCloudEventDecoder.decode(message)).get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertEquals(Set.copyOf(group.slots(InboxListenGroup.Role.DATA)),
                    new SdkListenMatchSet(group,pinned.snapshot()).matches(message).slots());
        }
    }
    @Test void overlappingWildcardStrategiesAreRefused() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                any: []
                until:
                  any: []
            """,false)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());
            assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(pinned.group(),pinned.snapshot()));
        }
    }
    @Test void literalSourceMatchesTheActualSdkAndWrongSourceIsRejected() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured(ListenGroupSnapshotsTest.ONE);var events=new InMemoryEvents()) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var filter=((ListenTask)run.context.task().task()).getListen().getTo().getOneEventConsumptionStrategy().getOne();
            var builder=events.listen(filter,run.app);var calls=new AtomicInteger();
            events.register(builder,event->calls.incrementAndGet(),run.context.workflow(),run.context.task());
            String source="urn:test:producer";String type=ListenGroupSnapshotsTest.TYPE;
            var accepted=new InboxMessage(group.task().tenant(),source,"id-1",type,group.task().instanceId(),"route",
                    "{\"specversion\":\"1.0\",\"source\":\""+source+"\",\"id\":\"id-1\",\"type\":\""+type+"\"}");
            events.publish(StoredCloudEventDecoder.decode(accepted)).get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertFalse(new SdkListenMatchSet(group,pinned.snapshot()).matches(accepted).slots().isEmpty());
            var rejected=receipt(group,type);events.publish(StoredCloudEventDecoder.decode(rejected)).get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertTrue(new SdkListenMatchSet(group,pinned.snapshot()).matches(rejected).slots().isEmpty());
        }
    }
    @Test void runtimeExpressionSourceRemainsUnsupported() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured(ListenGroupSnapshotsTest.ONE.replace("'urn:test:producer'", "'${ .source }'"),false)) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());
            assertThrows(IllegalArgumentException.class,()->new SdkListenMatchSet(pinned.group(),pinned.snapshot()));
        }
    }
    @Test void literalContentTypeAgreesWithActualSdk() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                one:
                  with: { type: org.openworkflow.agentic.group.a, datacontenttype: application/json }
            """);var events=new InMemoryEvents()) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var filter=((ListenTask)run.context.task().task()).getListen().getTo().getOneEventConsumptionStrategy().getOne();
            var calls=new AtomicInteger();events.register(events.listen(filter,run.app),event->calls.incrementAndGet(),run.context.workflow(),run.context.task());
            for(var contentType:List.of("application/json","text/plain")) {
                var message=new InboxMessage(group.task().tenant(),"urn:test:source","id-1",ListenGroupSnapshotsTest.TYPE,group.task().instanceId(),"route",
                        "{\"specversion\":\"1.0\",\"source\":\"urn:test:source\",\"id\":\"id-1\",\"type\":\""+ListenGroupSnapshotsTest.TYPE+"\",\"datacontenttype\":\""+contentType+"\"}");
                int before=calls.get();events.publish(StoredCloudEventDecoder.decode(message)).get(5,TimeUnit.SECONDS);
                assertEquals(calls.get()>before,!new SdkListenMatchSet(group,pinned.snapshot()).matches(message).slots().isEmpty());
            }
            assertEquals(1,calls.get());
        }
    }
    @Test void literalDataSchemaMatchesActualSdk() throws Exception {
        try(var run=new ListenGroupSnapshotsTest.Captured("""
            listen:
              to:
                one:
                  with: { type: org.openworkflow.agentic.group.a, dataschema: 'https://example.test/schema/v1' }
            """);var events=new InMemoryEvents()) {
            var pinned=run.prepare(ListenGroupSnapshotsTest.settings(),Map.of());var group=pinned.group();
            var filter=((ListenTask)run.context.task().task()).getListen().getTo().getOneEventConsumptionStrategy().getOne();
            var calls=new AtomicInteger();events.register(events.listen(filter,run.app),event->calls.incrementAndGet(),run.context.workflow(),run.context.task());
            for(var schema:List.of("https://example.test/schema/v1","https://example.test/schema/v2")) {
                var message=new InboxMessage(group.task().tenant(),"urn:test:source","id-1",ListenGroupSnapshotsTest.TYPE,group.task().instanceId(),"route",
                        "{\"specversion\":\"1.0\",\"source\":\"urn:test:source\",\"id\":\"id-1\",\"type\":\""+ListenGroupSnapshotsTest.TYPE+"\",\"dataschema\":\""+schema+"\"}");
                int before=calls.get();events.publish(StoredCloudEventDecoder.decode(message)).get(5,TimeUnit.SECONDS);
                assertEquals(calls.get()>before,!new SdkListenMatchSet(group,pinned.snapshot()).matches(message).slots().isEmpty());
            }
            assertEquals(1,calls.get());
        }
    }
}
