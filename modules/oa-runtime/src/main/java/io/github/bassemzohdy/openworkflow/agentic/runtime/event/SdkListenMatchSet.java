package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxListenGroup;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import io.serverlessworkflow.impl.TaskContext;
import io.serverlessworkflow.impl.WorkflowContext;
import io.serverlessworkflow.impl.WorkflowModel;
import io.serverlessworkflow.impl.events.CloudEventPredicate;
import io.serverlessworkflow.impl.events.ModelAwareCloudEventPredicate;
import io.serverlessworkflow.impl.events.TypeEventRegistration;
import java.util.*;

/**
 * Data-only SDK predicate evaluation for one complete, trusted listen registration set. The caller must bind
 * registrations to the pinned snapshot and approved routes, enforce a time bound and current authority, and pass the
 * complete result to the durable group store. A match is not a reservation, consumption proof or callback permission.
 * This object performs no database I/O and invokes no registration callback.
 */
public final class SdkListenMatchSet {
    public record Binding(InboxListenGroup.Slot slot,TypeEventRegistration registration) {
        public Binding {Objects.requireNonNull(slot);Objects.requireNonNull(registration);}
    }
    private record Pinned(InboxListenGroup.Slot slot,String type,CloudEventPredicate predicate,
                          List<CloudEventPredicate> correlations,WorkflowContext workflow,TaskContext task) { }
    private final InboxListenGroup group;
    private final List<Pinned> registrations;

    public SdkListenMatchSet(InboxListenGroup group,List<Binding> bindings) {
        this.group=Objects.requireNonNull(group);Objects.requireNonNull(bindings);
        if(bindings.isEmpty() || bindings.size()>InboxListenGroup.MAX_SLOTS)
            throw new IllegalArgumentException("physical registrations must be bounded and nonempty");
        var expected=new HashSet<InboxListenGroup.Slot>();
        expected.addAll(group.slots(InboxListenGroup.Role.DATA));expected.addAll(group.slots(InboxListenGroup.Role.UNTIL));
        var seen=new HashSet<InboxListenGroup.Slot>();var pinned=new ArrayList<Pinned>();
        WorkflowContext workflow=null;TaskContext task=null;boolean first=true;
        for(var binding:bindings) {
            Objects.requireNonNull(binding);var slot=binding.slot();var registration=binding.registration();
            if(!expected.contains(slot))throw new IllegalArgumentException("registration does not belong to this group");
            boolean wildcard=slot.role()==InboxListenGroup.Role.DATA?group.dataFilters().isEmpty():group.stopFilters().isEmpty();
            if(!seen.add(slot) && !wildcard)throw new IllegalArgumentException("duplicate physical registration for a filter slot");
            if(first){workflow=registration.workflow();task=registration.task();first=false;}
            else if(workflow!=registration.workflow() || task!=registration.task())
                throw new IllegalArgumentException("registrations must share the same SDK task context");
            var source=Objects.requireNonNull(registration.correlationPredicates());
            if(source.size()>InboxListenGroup.MAX_SLOTS)throw new IllegalArgumentException("too many correlation predicates");
            var correlations=List.copyOf(source);
            if(correlations.size()>InboxListenGroup.MAX_SLOTS)throw new IllegalArgumentException("too many correlation predicates");
            pinned.add(new Pinned(slot,registration.type(),Objects.requireNonNull(registration.predicate()),correlations,workflow,task));
        }
        if(!seen.equals(expected))throw new IllegalArgumentException("complete group registration set required");
        this.registrations=List.copyOf(pinned);
    }

    public Set<InboxListenGroup.Slot> matches(InboxMessage receipt) {
        Objects.requireNonNull(receipt);
        if(!group.task().tenant().equals(receipt.tenant()) || !group.task().instanceId().equals(receipt.instanceId()))
            throw new IllegalArgumentException("receipt belongs to another registry scope");
        var event=StoredCloudEventDecoder.decode(receipt);var matches=new LinkedHashSet<InboxListenGroup.Slot>();
        WorkflowModel model=null;
        for(var registration:registrations) {
            if(registration.type()!=null && !registration.type().equals(event.getType()))continue;
            if(!registration.predicate().test(event,registration.workflow(),registration.task()))continue;
            boolean accepted=true;
            for(var predicate:registration.correlations()) {
                boolean result;
                if(predicate instanceof ModelAwareCloudEventPredicate aware) {
                    if(model==null)model=Objects.requireNonNull(Objects.requireNonNull(registration.workflow(),"model-aware correlation requires workflow context")
                            .definition().application().modelFactory().from(event),"SDK model is required");
                    result=aware.test(model,registration.workflow(),registration.task());
                } else result=predicate.test(event,registration.workflow(),registration.task());
                if(!result){accepted=false;break;}
            }
            if(accepted)matches.add(registration.slot());
        }
        return Collections.unmodifiableSet(matches);
    }
}
