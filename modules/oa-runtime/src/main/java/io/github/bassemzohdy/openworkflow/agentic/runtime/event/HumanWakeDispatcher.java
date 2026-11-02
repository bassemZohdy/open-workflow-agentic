package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.HumanDecision;
import io.serverlessworkflow.impl.events.TypeEventRegistration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Explicit, isolated-schema composition for one human listen occurrence. The caller must supply the actual current
 * SDK registration and an authorized producer; this class is not CDI-wired and does not authenticate a caller.
 * Callback return is not checkpoint proof or permission to advance. The workflow must use the guarded JPA writer.
 */
public final class HumanWakeDispatcher {
    public enum Result { DISPATCHED, NOT_RESERVED, BUSY }
    private final HumanWakeStage stage;
    private final JdbcOwnedInbox owned;
    private final JdbcInboxTaskLease leases;
    private final Semaphore capacity;
    private final ConcurrentHashMap<InboxTask,Object> active=new ConcurrentHashMap<>();

    public HumanWakeDispatcher(HumanWakeStage stage,JdbcOwnedInbox owned,JdbcInboxTaskLease leases,int maxConcurrent) {
        this.stage=Objects.requireNonNull(stage);this.owned=Objects.requireNonNull(owned);this.leases=Objects.requireNonNull(leases);
        if(maxConcurrent<1||maxConcurrent>1024)throw new IllegalArgumentException("invalid human wake concurrency limit");
        capacity=new Semaphore(maxConcurrent);
    }

    /** Dispatches only the specified captured registration; no SDK broadcast is used. */
    public Result dispatch(HumanDecision decision,InboxWait wait,InboxTaskClaim claim,InboxListenGroup group,
                           String snapshot,TypeEventRegistration registration) {
        Objects.requireNonNull(decision);Objects.requireNonNull(wait);Objects.requireNonNull(claim);
        Objects.requireNonNull(group);Objects.requireNonNull(snapshot);Objects.requireNonNull(registration);
        if(!claim.task().equals(wait.task())||!group.task().equals(wait.task()))
            throw new IllegalArgumentException("human wake task identity differs");
        if(!capacity.tryAcquire())return Result.BUSY;
        var token=new Object();
        if(active.putIfAbsent(wait.task(),token)!=null){capacity.release();return Result.BUSY;}
        try {
            var message=HumanDecisionWake.message(decision,wait);
            if(HumanWakeSelection.select(decision,wait,group,snapshot,message).isEmpty())
                throw new IllegalArgumentException("human wake does not match the retained filter");
            var event=StoredCloudEventDecoder.decode(message);
            requireRegistration(registration,wait,event);
            stage.stage(decision,wait,claim,group,snapshot);
            var reserved=owned.reserve(claim,wait);
            if(reserved.isEmpty())return Result.NOT_RESERVED;
            if(!message.equals(reserved.get().receipt()))throw new IllegalStateException("reserved human wake changed");
            InboxCheckpointMarker.install(registration.workflow(),registration.task(),reserved.get(),claim);
            // Both audit and predicate code can block. A stale lease cannot authorize a new callback.
            requireRegistration(registration,wait,event);
            leases.requireLive(claim);
            registration.consumer().accept(event);
            return Result.DISPATCHED;
        } finally {active.remove(wait.task(),token);capacity.release();}
    }

    private static void requireRegistration(TypeEventRegistration registration,InboxWait wait,CloudEvent event) {
        var workflow=registration.workflow();var task=registration.task();var definition=workflow.definition().id();
        if(task.isCompleted()||!wait.applicationId().equals(workflow.definition().application().id())
                ||!wait.engineInstanceId().equals(workflow.instanceData().id())
                ||!wait.namespace().equals(definition.namespace())||!wait.definition().equals(definition.name())
                ||!wait.version().equals(definition.version())||!wait.taskPosition().equals(task.position().jsonPointer())
                ||wait.iteration()!=task.iteration()||!event.getType().equals(registration.type())
                ||!registration.correlationPredicates().isEmpty()||!registration.predicate().test(event,workflow,task))
            throw new IllegalStateException("SDK registration does not match the selected human wait");
    }
}
