package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxListenGroup;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Actual reader/application contexts; registry/approved deployment data are explicit unit fixtures. No SQL or authority claim. */
class ListenGroupSnapshotsTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String TYPE="org.openworkflow.agentic.group.a";
    static String document(String task) {return """
        document: { dsl: '1.0.3', namespace: agentic-test, name: snapshot, version: '1.0.0' }
        do:
          - awaitEvents:
        """+task.indent(6);}
    static final String ONE="""
        listen:
          to:
            one:
              with: { type: org.openworkflow.agentic.group.a, source: 'urn:test:producer' }
        timeout: { after: PT30S }
        """;
    record Context(WorkflowContext workflow,TaskContext task) { }
    static final class Captured implements AutoCloseable {
        final WorkflowApplication app;final Context context;final InstanceRecord row;
        Captured(String task) throws Exception {this(task,true);}
        Captured(String task,boolean validate) throws Exception {
            var captured=new CompletableFuture<Context>();
            var broker=new InMemoryEvents() {
                public TypeEventRegistration register(TypeEventRegistrationBuilder b,Consumer<CloudEvent> callback,
                                                       WorkflowContext w,TaskContext t) {
                    var result=super.register(b,callback,w,t);captured.complete(new Context(w,t));return result;
                }
            };
            app=WorkflowApplication.builder().withEventConsumer(broker).disableLifeCycleCEPublishing().disableStatusChangePublishing().build();
            var instance=app.workflowDefinition(validate?WorkflowReader.readWorkflowFromString(document(task),WorkflowFormat.YAML)
                    :WorkflowReader.noValidation().read(document(task),WorkflowFormat.YAML))
                    .instance(Map.of("neverSnapshotInput","private input"));
            instance.start();context=captured.get(5,TimeUnit.SECONDS);
            row=InstanceRecord.starting(UlidGenerator.shared().next(),new StartRequest("tenant",new Subject(SubjectKind.USER,"alice"),
                    new DefinitionRef("agentic-test/snapshot","1.0.0"),Map.of("neverSnapshotRegistryInput","private row"),"snapshot"),Instant.now())
                    .successor(Instant.now(),InstanceStatus.RUNNING,instance.id(),0,null,null);
        }
        ListenGroupSnapshots.Prepared prepare(EventIngressSettings settings,Map<String,String> correlation) {
            return new ListenGroupSnapshots(settings).prepare(row,context.workflow(),context.task(),correlation);
        }
        public void close() {app.close();}
    }
    static EventIngressSettings.Producer producer(String tenant,String subject,Set<String> definitions) {
        return new EventIngressSettings.Producer() {
            public String tenant(){return tenant;} public SubjectKind kind(){return SubjectKind.SERVICE;}
            public String subject(){return subject;} public String source(){return "urn:test:producer";}
            public Set<String> types(){return Set.of(TYPE,"org.openworkflow.agentic.group.b");}
            public Set<String> definitions(){return definitions;} public Set<String> correlations(){return Set.of("order","invoice");}
        };
    }
    static EventIngressSettings settings(Duration retention,Map<String,EventIngressSettings.Producer> producers) {
        return new EventIngressSettings() {
            public Duration retention(){return retention;} public String policyFolder(){throw new AssertionError("no policy initialization");}
            public Map<String,Producer> producers(){return producers;}
        };
    }
    static EventIngressSettings settings() {return settings(Duration.ofHours(1),Map.of("producer",producer("tenant","producer",
            Set.of("agentic-test/snapshot"))));}
    static String fingerprint(String task,Map<String,String> parameters) throws Exception {
        try(var run=new Captured(task)) {return run.prepare(settings(),parameters).group().configurationFingerprint();}
    }
    @Test void actualAllKeepsDuplicateOrdinalIdentityAndPinsFullTaskWithoutWorkflowInput() throws Exception {
        try(var run=new Captured("""
            listen:
              to:
                all:
                  - with: { type: org.openworkflow.agentic.group.a }
                  - with: { type: org.openworkflow.agentic.group.a }
            timeout: { after: PT30S }
            """)) {
            var prepared=run.prepare(settings(),Map.of("order","order-17"));var group=prepared.group();
            assertEquals(InboxListenGroup.Strategy.ALL,group.strategy());assertEquals(2,group.dataFilters().size());
            assertEquals(group.dataFilters().get(0),group.dataFilters().get(1));
            assertNotEquals(group.slots(InboxListenGroup.Role.DATA).get(0).key(),group.slots(InboxListenGroup.Role.DATA).get(1).key());
            assertEquals(run.row.id(),group.task().instanceId());assertEquals(run.context.task().position().jsonPointer(),group.task().taskPosition());
            assertEquals(run.context.task().iteration(),group.task().iteration());assertEquals(run.row.engineInstanceId(),group.task().engineInstanceId());
            JsonNode snapshot=JSON.readTree(prepared.snapshot());assertTrue(snapshot.path("task").has("timeout"));
            assertEquals("order-17",snapshot.path("correlation").path("order").asText());
            assertFalse(prepared.snapshot().contains("private input"));assertFalse(prepared.snapshot().contains("private row"));
            assertEquals(group.configurationFingerprint(),InboxListenGroup.snapshotFingerprint(prepared.snapshot()));
        }
    }
    @Test void actualWildcardAndStoppingStrategyKeepDistinctRolesAndExpressionIdentity() throws Exception {
        try(var run=new Captured("""
            listen:
              to:
                any: []
                until:
                  all:
                    - with: { type: org.openworkflow.agentic.group.a }
                    - with: { type: org.openworkflow.agentic.group.a }
            """)) {
            var group=run.prepare(settings(),Map.of()).group();assertTrue(group.dataFilters().isEmpty());
            assertEquals(1,group.slots(InboxListenGroup.Role.DATA).size());assertEquals(InboxListenGroup.Stop.ALL,group.stop());
            assertEquals(2,group.slots(InboxListenGroup.Role.UNTIL).size());
            assertEquals(InboxListenGroup.Role.UNTIL,group.slots(InboxListenGroup.Role.UNTIL).getFirst().role());
        }
        try(var run=new Captured("""
            listen:
              read: envelope
              to:
                any:
                  - with: { type: org.openworkflow.agentic.group.a }
                until: '${ . | length >= 2 }'
            """)) {
            var group=run.prepare(settings(),Map.of()).group();assertEquals(InboxListenGroup.Stop.EXPRESSION,group.stop());
            assertNotNull(group.expressionFingerprint());assertTrue(group.slots(InboxListenGroup.Role.UNTIL).isEmpty());
        }
    }
    @Test void canonicalObjectKeysIgnoreDeclarationOrderWhileArrayOrderRemainsSignificant() throws Exception {
        String first=fingerprint(ONE,Map.of("b","2","a","1"));
        assertEquals(first,fingerprint(ONE.replace("type: "+TYPE+", source: 'urn:test:producer'",
                "source: 'urn:test:producer', type: "+TYPE),new LinkedHashMap<>(Map.of("a","1","b","2"))));
        String all="""
            listen:
              to:
                all:
                  - with: { type: org.openworkflow.agentic.group.a }
                  - with: { type: org.openworkflow.agentic.group.b }
            """;
        assertNotEquals(fingerprint(all,Map.of()),fingerprint(all.replace("group.a","group.temp").replace("group.b","group.a")
                .replace("group.temp","group.b"),Map.of()));
    }
    @Test void snapshotOnlyIncludesRelevantValidatedProducerRoutesAndIsImmutable() throws Exception {
        try(var run=new Captured(ONE)) {
            var relevant=producer("tenant","producer",Set.of("agentic-test/snapshot"));
            var baseline=run.prepare(settings(),Map.of()).snapshot();
            var changed=new LinkedHashMap<String,EventIngressSettings.Producer>();changed.put("renamed",relevant);
            changed.put("foreign",producer("foreign-tenant","foreign-secret-subject",Set.of("agentic-test/snapshot")));
            changed.put("other-definition",producer("tenant","unrelated-subject",Set.of("agentic-test/elsewhere")));
            assertEquals(baseline,run.prepare(settings(Duration.ofHours(1),changed),Map.of()).snapshot());
            changed.put("renamed",producer("tenant","producer",Set.of("agentic-test/snapshot","agentic-test/other")));
            assertEquals(baseline,run.prepare(settings(Duration.ofHours(1),changed),Map.of()).snapshot());
            assertFalse(baseline.contains("foreign-secret-subject"));assertFalse(baseline.contains("unrelated-subject"));
            changed.put("renamed",producer("tenant","new-producer",Set.of("agentic-test/snapshot")));
            assertNotEquals(baseline,run.prepare(settings(Duration.ofHours(1),changed),Map.of()).snapshot());
            assertNotEquals(baseline,run.prepare(settings(Duration.ofHours(2),Map.of("p",relevant)),Map.of()).snapshot());
            Map<String,String> mutable=new HashMap<>(Map.of("order","original"));var pinned=run.prepare(settings(),mutable);
            mutable.put("order","changed");assertEquals("original",JSON.readTree(pinned.snapshot()).path("correlation").path("order").asText());
        }
    }
    @Test void readForeachTimeoutAndResolvedCorrelationChangesAllChangeThePinnedIdentity() throws Exception {
        String original=fingerprint(ONE,Map.of("order","17"));
        assertNotEquals(original,fingerprint(ONE.replace("listen:","listen:\n  read: envelope"),Map.of("order","17")));
        assertNotEquals(original,fingerprint(ONE.replace("PT30S","PT31S"),Map.of("order","17")));
        assertNotEquals(original,fingerprint(ONE,Map.of("order","18")));
        String foreach=ONE+"""
            foreach:
              item: event
              at: ordinal
              do:
                - processItem:
                    set: { result: '${ . }' }
            """;
        assertNotEquals(original,fingerprint(foreach,Map.of("order","17")));
        assertNotEquals(fingerprint(foreach,Map.of()),fingerprint(foreach.replace("result:","other:"),Map.of()));
    }
    @Test void foreignRegistryAndSchemaForbiddenNestedUntilAreRefusedWithoutIo() throws Exception {
        try(var run=new Captured(ONE)) {
            var factory=new ListenGroupSnapshots(settings());var row=run.row;
            assertThrows(IllegalArgumentException.class,() -> factory.prepare(row.successor(Instant.now(),InstanceStatus.RUNNING,"foreign",0,null,null),
                    run.context.workflow(),run.context.task(),Map.of()));
            var foreign=new InstanceRecord(row.id(),row.tenant(),row.owner(),row.idempotencyKey(),row.requestFingerprint(),
                    new DefinitionRef("agentic-test/foreign","1.0.0"),row.input(),row.status(),row.engineInstanceId(),0,null,null,null,0,row.createdAt(),row.updatedAt());
            assertThrows(IllegalArgumentException.class,() -> factory.prepare(foreign,run.context.workflow(),run.context.task(),Map.of()));
            assertThrows(IllegalArgumentException.class,() -> run.prepare(settings(),Map.of("order","x".repeat(257))));
            assertThrows(IllegalArgumentException.class,() -> run.prepare(settings(Duration.ZERO,Map.of()),Map.of()));
        }
        String forbidden="""
            listen:
              to:
                any: []
                until:
                  any: []
                  until:
                    one:
                      with: { type: org.openworkflow.agentic.group.a }
            """;
        assertThrows(IllegalArgumentException.class,() -> WorkflowReader.readWorkflowFromString(document(forbidden),WorkflowFormat.YAML));
        // Deliberately bypass the reader's schema validation: the mapper must defend the invariant too.
        try(var run=new Captured(forbidden,false)) {
            assertThrows(IllegalArgumentException.class,() -> run.prepare(settings(),Map.of()),"schema-forbidden nested until must not be flattened");
        }
    }
}
