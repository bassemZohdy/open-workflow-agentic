package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.cloudevents.CloudEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.HumanDecisionWake;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxWait;
import io.serverlessworkflow.api.types.EventFilter;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.events.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Explicit SDK event-consumer wrapper for captured human listen registrations. Human registrations are not added to
 * the delegate's type broadcast. Lookup is local process state, never tenant authentication or delivery authority.
 */
public final class HumanWakeRegistrations implements EventConsumer<TypeEventRegistration,TypeEventRegistrationBuilder> {
    private record Key(String application,String engine,String namespace,String definition,String version,String position,int iteration) { }
    private final EventConsumer<TypeEventRegistration,TypeEventRegistrationBuilder> delegate;
    private final int limit;
    private final Map<Key,TypeEventRegistration> byKey=new HashMap<>();
    private final IdentityHashMap<TypeEventRegistration,Key> byIdentity=new IdentityHashMap<>();
    private final Map<Key,TypeEventRegistration> invoking=new HashMap<>();
    private int detachedInvocations;
    private boolean closed;

    public HumanWakeRegistrations(EventConsumer<TypeEventRegistration,TypeEventRegistrationBuilder> delegate,int limit) {
        this.delegate=Objects.requireNonNull(delegate);
        if(limit<1||limit>100_000)throw new IllegalArgumentException("invalid human registration limit");
        this.limit=limit;
    }
    @Override public TypeEventRegistrationBuilder listen(EventFilter filter,WorkflowApplication app) {return delegate.listen(filter,app);}
    @Override public Collection<TypeEventRegistrationBuilder> listenToAll(WorkflowApplication app) {return delegate.listenToAll(app);}
    @Override public TypeEventRegistration register(TypeEventRegistrationBuilder builder,Consumer<CloudEvent> callback,
                                                     WorkflowContext workflow,TaskContext task) {
        Objects.requireNonNull(builder);Objects.requireNonNull(callback);
        synchronized(this) {
            if(closed)throw new IllegalStateException("human registration registry is closed");
            if(!HumanDecisionWake.TYPE.equals(builder.type()))return delegate.register(builder,callback,workflow,task);
            if(workflow==null||task==null||task.isCompleted()||!builder.correlationPredicates().isEmpty())
                throw new IllegalArgumentException("human wake requires a task registration without correlations");
            Key key=key(workflow,task);
            if(byKey.containsKey(key)||invoking.containsKey(key))throw new IllegalStateException("human task already has a captured registration or callback");
            if(byKey.size()+detachedInvocations>=limit)throw new IllegalStateException("human registration capacity exhausted");
            var registration=new TypeEventRegistration(builder.type(),callback,builder.cePredicate(),builder.correlationPredicates(),workflow,task);
            byKey.put(key,registration);byIdentity.put(registration,key);return registration;
        }
    }
    @Override public synchronized void unregister(TypeEventRegistration registration) {
        Objects.requireNonNull(registration);
        var key=byIdentity.remove(registration);
        if(key!=null){
            byKey.remove(key,registration);
            if(invoking.get(key)==registration)detachedInvocations++;
            return;
        }
        // An already removed human registration must never be sent to the delegate.
        if(!HumanDecisionWake.TYPE.equals(registration.type()))delegate.unregister(registration);
    }
    public synchronized Optional<Handle> find(InboxWait wait) {
        Objects.requireNonNull(wait);if(closed)return Optional.empty();
        var key=new Key(wait.applicationId(),wait.engineInstanceId(),wait.namespace(),wait.definition(),wait.version(),wait.taskPosition(),wait.iteration());
        var registration=byKey.get(key);
        if(registration==null||registration.task().isCompleted())return Optional.empty();
        return Optional.of(new Handle(this,key,registration));
    }
    private synchronized boolean current(Key key,TypeEventRegistration registration) {
        return !closed&&byKey.get(key)==registration&&!registration.task().isCompleted();
    }
    private synchronized boolean begin(Key key,TypeEventRegistration registration) {
        if(!current(key,registration)||invoking.containsKey(key))return false;
        invoking.put(key,registration);return true;
    }
    private synchronized void end(Key key,TypeEventRegistration registration) {
        if(invoking.get(key)==registration) {
            invoking.remove(key);
            if(byKey.get(key)!=registration)detachedInvocations--;
        }
    }
    private static Key key(WorkflowContext workflow,TaskContext task) {
        var id=workflow.definition().id();
        return new Key(workflow.definition().application().id(),workflow.instanceData().id(),id.namespace(),id.name(),id.version(),
                task.position().jsonPointer(),task.iteration());
    }
    /** A revocable local capture; obtaining it is not a lease or authorization. */
    public static final class Handle {
        private final HumanWakeRegistrations owner;private final Key key;private final TypeEventRegistration registration;
        private Handle(HumanWakeRegistrations owner,Key key,TypeEventRegistration registration){this.owner=owner;this.key=key;this.registration=registration;}
        public boolean isCurrent(){return owner.current(key,registration);}
        TypeEventRegistration registration(){return registration;}
        boolean begin(){return owner.begin(key,registration);}
        void end(){owner.end(key,registration);}
    }
    @Override public void close() throws Exception {
        synchronized(this) {if(closed)return;closed=true;byKey.clear();byIdentity.clear();invoking.clear();detachedInvocations=0;}
        delegate.close();
    }
}
