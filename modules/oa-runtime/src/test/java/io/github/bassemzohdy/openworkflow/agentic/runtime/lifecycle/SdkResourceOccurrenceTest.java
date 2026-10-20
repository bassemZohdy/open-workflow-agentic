package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.serverlessworkflow.api.*;
import io.serverlessworkflow.api.types.ForTask;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.lifecycle.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/**
 * Actual pinned SDK full-workflow observations, not a production invocation identity or durable retry/restore proof.
 * An SDK task-entry counter can change on retry, while a genuine foreach element also changes the counter. Neither
 * blindly hashing all counters nor dropping them all derives the complete stable business occurrence.
 */
class SdkResourceOccurrenceTest {
    private static final Ulid INSTANCE=Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final String PREFIX="""
            document:
              dsl: '1.0.3'
              namespace: agentic-test
              name: resource-occurrence
              version: '1.0.0'
              title: Observe pinned SDK task counters
              summary: Deterministic counter fixtures; no external calls, credentials or business effects.
            use:
              errors:
                fixtureFailure: { type: 'urn:test:resource-occurrence', status: 503 }
            """;
    static final String RETRY="""
            do:
              - retryStep:
                  try:
                    - failStep:
                        raise: { error: fixtureFailure }
                  catch:
                    retry:
                      delay: { milliseconds: 1 }
                      limit: { attempt: { count: 1 } }
            """;
    static final String FOREACH="""
            do:
              - iterateItems:
                  for: { each: item, at: index, in: '${ [10,20] }' }
                  do:
                    - observeItem:
                        set: { observed: true }
            """;
    static final String RETRY_FOREACH="""
            do:
              - retryGroup:
                  try:
                    - iterateItems:
                        for: { each: item, at: index, in: '${ [10] }' }
                        do:
                          - observeItem:
                              set: { observed: true }
                          - failStep:
                              raise: { error: fixtureFailure }
                  catch:
                    retry:
                      delay: { milliseconds: 1 }
                      limit: { attempt: { count: 1 } }
            """;
    record Frame(String position,int iteration,Integer forIndex) { }
    record Attempt(String name,String position,int iteration,int retry,List<Frame> parents,ResourceCallScope naiveScope) { }
    record Result(WorkflowStatus status,List<Attempt> attempts) { }

    static Result run(String body,boolean fails) throws Exception {
        var attempts=new CopyOnWriteArrayList<Attempt>();
        var listener=new WorkflowExecutionListener() {
            void capture(TaskEvent event) {
                var task=(TaskContext)event.taskContext();
                var workflow=(WorkflowContext)event.workflowContext();
                var parents=new ArrayList<Frame>();
                for(var p=task.parent();p.isPresent();p=p.get().parent()) {
                    var parent=p.get();Integer index=null;
                    if(parent.task() instanceof ForTask loop) {
                        var value=parent.variables().get(loop.getFor().getAt());
                        assertInstanceOf(Integer.class,value);index=(Integer)value;
                    }
                    parents.add(new Frame(parent.position().jsonPointer(),parent.iteration(),index));
                }
                var definition=workflow.definition().id();
                var scope=new ResourceCallScope("fixture-tenant",INSTANCE,new Subject(SubjectKind.USER,"alice"),
                        workflow.definition().application().id(),workflow.instanceData().id(),definition.namespace(),
                        definition.name(),definition.version(),task.position().jsonPointer(),task.iteration(),
                        "candidate-same-logical-occurrence","fixture-audience","run","a".repeat(64),"b".repeat(64));
                attempts.add(new Attempt(task.taskName(),task.position().jsonPointer(),task.iteration(),task.retryAttempt(),
                        List.copyOf(parents),scope));
            }
            public void onTaskStarted(TaskStartedEvent event) {capture(event);}
            public void onTaskRetried(TaskRetriedEvent event) {capture(event);}
        };
        try(var app=WorkflowApplication.builder().withListener(listener).disableLifeCycleCEPublishing()
                .disableStatusChangePublishing().build()) {
            var instance=app.workflowDefinition(WorkflowReader.readWorkflowFromString(PREFIX+body,WorkflowFormat.YAML)).instance(Map.of());
            try {instance.start().get(5,TimeUnit.SECONDS);}
            catch(ExecutionException failure) {
                if(!fails) throw failure;
                assertInstanceOf(WorkflowException.class,failure.getCause());
            }
            assertEquals(fails?WorkflowStatus.FAULTED:WorkflowStatus.COMPLETED,instance.status());
            return new Result(instance.status(),List.copyOf(attempts));
        }
    }
    static List<Attempt> named(Result result,String task) {
        return result.attempts().stream().filter(a->a.name().equals(task)).toList();
    }
    @Test void retryReentryChangesSdkCounterAndWouldChangeANaivelyDerivedCallKey() throws Exception {
        var observed=named(run(RETRY,true),"failStep");assertEquals(2,observed.size());
        assertEquals(List.of(1,2),observed.stream().map(Attempt::iteration).toList());
        assertEquals(List.of(0,1),observed.stream().map(Attempt::retry).toList());
        assertEquals(observed.get(0).position(),observed.get(1).position());
        assertEquals(observed.get(0).naiveScope().logicalOccurrence(),observed.get(1).naiveScope().logicalOccurrence());
        assertNotEquals(observed.get(0).naiveScope().key(),observed.get(1).naiveScope().key(),
                "negative control: directly copying the changing SDK counter would create a new call on retry");
    }
    @Test void genuineForEachElementsChangeIndexWhileTheirParentTaskEntryCounterStaysOne() throws Exception {
        var observed=named(run(FOREACH,false),"observeItem");assertEquals(2,observed.size());
        assertEquals(List.of(1,2),observed.stream().map(Attempt::iteration).toList());
        assertEquals(List.of(0,0),observed.stream().map(Attempt::retry).toList());
        assertEquals(List.of(0,1),observed.stream().map(a->a.parents().getFirst().forIndex()).toList());
        assertEquals(List.of(1,1),observed.stream().map(a->a.parents().getFirst().iteration()).toList());
    }
    @Test void retryOfEnclosingGroupRepeatsSameForEachIndexWithChangedLeafAndParentCounters() throws Exception {
        var observed=named(run(RETRY_FOREACH,true),"observeItem");assertEquals(2,observed.size());
        assertEquals(List.of(0,0),observed.stream().map(a->a.parents().getFirst().forIndex()).toList());
        assertEquals(List.of(1,2),observed.stream().map(Attempt::iteration).toList());
        assertEquals(List.of(1,2),observed.stream().map(a->a.parents().getFirst().iteration()).toList());
        assertEquals(List.of(0,1),observed.stream().map(Attempt::retry).toList());
        assertNotEquals(observed.get(0).parents(),observed.get(1).parents(),
                "negative control: hashing the changing parent counters also misidentifies this retry");
    }
}
