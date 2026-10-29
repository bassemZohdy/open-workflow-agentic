package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxListenGroup;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxMessage;
import java.io.IOException;
import java.util.*;

/**
 * Fail-closed literal filter preparation from a retained configuration. No SDK predicate, workflow context,
 * callback, I/O or authority is used. Unsupported filters (including correlations and expressions) are refused.
 * This result is not delivery permission; the store still needs complete-match replay protection before integration.
 */
public final class SdkListenMatchSet {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private record Filter(InboxListenGroup.Slot slot,String type,String id,String subject) { }
    private final InboxListenGroup group;
    private final List<Filter> filters;

    public SdkListenMatchSet(InboxListenGroup group,String snapshot) {
        this.group=Objects.requireNonNull(group);
        if(!group.configurationFingerprint().equals(InboxListenGroup.snapshotFingerprint(snapshot)))
            throw new IllegalArgumentException("snapshot does not identify group");
        final JsonNode root;
        try {root=JSON.readTree(snapshot);} catch(IOException invalid) {throw new IllegalArgumentException("invalid snapshot",invalid);}
        if(root==null || !root.isObject() || !"oa.inbox.listen-snapshot.v1".equals(root.path("format").asText()))
            throw new IllegalArgumentException("unsupported snapshot");
        var to=root.path("task").path("listen").path("to");
        requireStrategyFields(to,true);
        var data=ListenGroupSnapshots.shape(to);
        if(data.strategy()!=group.strategy() || !data.filters().equals(group.dataFilters()))
            throw new IllegalArgumentException("snapshot data registrations differ from group");
        var until=to.get("until");
        if(until==null || until.isNull()) {
            if(group.stop()!=InboxListenGroup.Stop.NONE) throw new IllegalArgumentException("snapshot stop differs from group");
        } else {
            if(until.isTextual()) throw new IllegalArgumentException("expression stop unsupported");
            requireStrategyFields(until,false);
            var stop=ListenGroupSnapshots.shape(until);
            if(!stop.strategy().name().equals(group.stop().name()) || !stop.filters().equals(group.stopFilters()))
                throw new IllegalArgumentException("snapshot stop registrations differ from group");
        }
        var compiled=new ArrayList<Filter>();
        append(compiled,group.slots(InboxListenGroup.Role.DATA),to,data.strategy());
        if(until!=null && !until.isNull()) append(compiled,group.slots(InboxListenGroup.Role.UNTIL),until,
                InboxListenGroup.Strategy.valueOf(group.stop().name()));
        this.filters=List.copyOf(compiled);
    }

    private static void requireStrategyFields(JsonNode node,boolean allowUntil) {
        if(!node.isObject())throw new IllegalArgumentException("strategy object required");
        var allowed=allowUntil?Set.of("one","any","all","until"):Set.of("one","any","all");
        var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);
        if(!allowed.containsAll(names))throw new IllegalArgumentException("unsupported strategy field");
    }

    private static void append(List<Filter> out,List<InboxListenGroup.Slot> slots,JsonNode strategy,InboxListenGroup.Strategy kind) {
        var node=strategy.path(kind.name().toLowerCase(Locale.ROOT));
        if(kind!=InboxListenGroup.Strategy.ONE && node.isArray() && node.isEmpty()) {
            out.add(new Filter(slots.getFirst(),null,null,null));return;
        }
        var nodes=new ArrayList<JsonNode>();if(kind==InboxListenGroup.Strategy.ONE)nodes.add(node);else node.forEach(nodes::add);
        if(nodes.size()!=slots.size()) throw new IllegalArgumentException("slot count differs from snapshot");
        for(int i=0;i<nodes.size();i++) {
            var filter=nodes.get(i);if(!filter.isObject() || filter.size()!=1 || !filter.has("with"))
                throw new IllegalArgumentException("unsupported filter or correlation");
            var with=filter.get("with");if(!with.isObject())throw new IllegalArgumentException("event properties required");
            var names=new HashSet<String>();with.fieldNames().forEachRemaining(names::add);
            if(!Set.of("type","id","subject").containsAll(names))throw new IllegalArgumentException("unsupported event property");
            var type=literal(with,"type");var id=literal(with,"id");var subject=literal(with,"subject");
            // The pinned SDK registers a null type as an unconditional broadcast and discards its predicate.
            if(type==null && (id!=null || subject!=null))throw new IllegalArgumentException("untyped filtered broadcast unsupported");
            out.add(new Filter(slots.get(i),type,id,subject));
        }
    }
    private static String literal(JsonNode node,String name) {
        var value=node.get(name);if(value==null || value.isNull())return null;
        if(!value.isTextual())throw new IllegalArgumentException("event property must be literal text");
        return value.textValue();
    }
    /** Opaque result tied to the exact group and receipt; only this selector can construct it. */
    public static final class Selection {
        private final InboxListenGroup group;
        private final String receiptFingerprint;
        private final Set<InboxListenGroup.Slot> slots;
        private Selection(InboxListenGroup group,InboxMessage receipt,Set<InboxListenGroup.Slot> slots) {
            this.group=group;this.receiptFingerprint=receipt.fingerprint();this.slots=Set.copyOf(slots);
        }
        public Set<InboxListenGroup.Slot> slots() {return slots;}
        void require(InboxListenGroup group,InboxMessage receipt) {
            if(!this.group.equals(group) || !receiptFingerprint.equals(receipt.fingerprint()))
                throw new IllegalArgumentException("selection belongs to another group or receipt");
        }
    }
    public Selection matches(InboxMessage receipt) {
        Objects.requireNonNull(receipt);
        if(!group.task().tenant().equals(receipt.tenant()) || !group.task().instanceId().equals(receipt.instanceId()))
            throw new IllegalArgumentException("receipt belongs to another registry scope");
        var event=StoredCloudEventDecoder.decode(receipt);var matches=new LinkedHashSet<InboxListenGroup.Slot>();
        for(var f:filters) if((f.type()==null || f.type().equals(event.getType()))
                && (f.id()==null || f.id().equals(event.getId())) && (f.subject()==null || f.subject().equals(event.getSubject()))) matches.add(f.slot());
        return new Selection(group,receipt,matches);
    }
}
