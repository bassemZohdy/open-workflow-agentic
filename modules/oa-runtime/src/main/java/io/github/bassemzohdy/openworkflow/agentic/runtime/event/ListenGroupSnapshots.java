package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceRecord;
import io.serverlessworkflow.api.types.ListenTask;
import io.serverlessworkflow.impl.TaskContext;
import io.serverlessworkflow.impl.WorkflowContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

/**
 * Preparation of immutable group data from a trusted registry row and the actual SDK task. Does no I/O, matching,
 * admission or callback release. The runtime caller must load/authorize the row and explicitly resolve only approved
 * correlation parameters. Restores load the retained snapshot; they must not rebuild it from changed deployment defaults.
 */
@ApplicationScoped
public class ListenGroupSnapshots {
    // SDK mixins preserve union values; its default disables empty arrays, which would erase valid ANY [].
    private static final ObjectMapper JSON=io.serverlessworkflow.api.WorkflowFormat.JSON.mapper().copy()
            .enable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS).disable(SerializationFeature.INDENT_OUTPUT);
    private final EventIngressSettings settings;
    @Inject public ListenGroupSnapshots(EventIngressSettings settings) {this.settings=Objects.requireNonNull(settings);}

    public record Prepared(InboxListenGroup group,String snapshot) {
        public Prepared {
            Objects.requireNonNull(group);
            if(!group.configurationFingerprint().equals(InboxListenGroup.snapshotFingerprint(snapshot)))
                throw new IllegalArgumentException("snapshot and group identity differ");
        }
    }

    public Prepared prepare(InstanceRecord row,WorkflowContext workflow,TaskContext context,Map<String,String> correlation) {
        Objects.requireNonNull(row);Objects.requireNonNull(workflow);Objects.requireNonNull(context);
        var id=workflow.definition().id();
        if(!Objects.equals(row.engineInstanceId(),workflow.instanceData().id())
                || !row.definition().namespace().equals(id.namespace()) || !row.definition().name().equals(id.name())
                || !row.definition().version().equals(id.version()))
            throw new IllegalArgumentException("registry and SDK instance/definition differ");
        if(!(context.task() instanceof ListenTask listen)) throw new IllegalArgumentException("listen task required");
        var task=new InboxTask(row.tenant(),row.id(),workflow.definition().application().id(),workflow.instanceData().id(),
                id.namespace(),id.name(),id.version(),context.position().jsonPointer(),context.iteration());
        var parameters=new TreeMap<String,String>();
        Objects.requireNonNull(correlation);
        if(correlation.size()>64) throw new IllegalArgumentException("too many correlation parameters");
        correlation.forEach((key,value) -> parameters.put(Checks.requireText("correlation name",key,256),
                Checks.requireText("correlation value",value,256)));
        JsonNode ast=JSON.valueToTree(listen);
        Shape data=shape(ast.path("listen").path("to"));
        InboxListenGroup.Stop stop=InboxListenGroup.Stop.NONE;List<String> stopping=List.of();String expression=null;
        JsonNode until=ast.path("listen").path("to").get("until");
        if(until!=null && !until.isNull()) {
            if(data.strategy()!=InboxListenGroup.Strategy.ANY) throw new IllegalArgumentException("until requires any");
            if(until.isTextual()) {
                stop=InboxListenGroup.Stop.EXPRESSION;expression=fingerprint("oa.inbox.until-expression.v1",until);
            } else {
                // OWS 1.0.3 explicitly forbids until inside an event-based stopping strategy.
                if(until.has("until")) throw new IllegalArgumentException("OWS forbids nested until");
                Shape end=shape(until);stop=InboxListenGroup.Stop.valueOf(end.strategy().name());stopping=end.filters();
            }
        }
        var snapshot=new TreeMap<String,Object>();snapshot.put("format","oa.inbox.listen-snapshot.v1");snapshot.put("task",ast);
        snapshot.put("producers",EventIngress.producerSnapshot(settings,row.tenant(),row.definition().definitionId()));
        snapshot.put("correlation",parameters);
        String text=canonical(JSON.valueToTree(snapshot));
        var group=new InboxListenGroup(task,data.strategy(),data.filters(),stop,stopping,expression,
                InboxListenGroup.snapshotFingerprint(text));
        return new Prepared(group,text);
    }

    static record Shape(InboxListenGroup.Strategy strategy,List<String> filters) { }
    static Shape shape(JsonNode strategy) {
        if(!strategy.isObject()) throw new IllegalArgumentException("event consumption strategy required");
        var names=List.of("one","any","all").stream().filter(strategy::hasNonNull).toList();
        if(names.size()!=1) throw new IllegalArgumentException("exactly one event consumption strategy required");
        String name=names.getFirst();JsonNode filters=strategy.get(name);var result=new ArrayList<String>();
        if(name.equals("one")) result.add(filter(filters));
        else {
            if(!filters.isArray() || filters.size()>InboxListenGroup.MAX_SLOTS)
                throw new IllegalArgumentException("bounded event filter array required");
            filters.forEach(f -> result.add(filter(f)));
        }
        return new Shape(InboxListenGroup.Strategy.valueOf(name.toUpperCase(Locale.ROOT)),List.copyOf(result));
    }
    private static String filter(JsonNode node) {
        if(!node.isObject()) throw new IllegalArgumentException("event filter object required");
        return fingerprint("oa.inbox.event-filter.v1",node);
    }
    static String fingerprint(String domain,JsonNode node) {
        return CanonicalEncoder.sha256Hex(new CanonicalEncoder(domain).text(canonical(node)).toBytes());
    }
    /** Object keys sorted recursively; arrays (including duplicate filters) retain declaration order. */
    private static String canonical(JsonNode node) {
        try {return JSON.writeValueAsString(ordered(node));}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {throw new IllegalArgumentException("invalid listen snapshot",invalid);}
    }
    private static JsonNode ordered(JsonNode node) {
        if(node.isObject()) {
            var out=JSON.createObjectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(k -> out.set(k,ordered(node.get(k))));return out;
        }
        if(node.isArray()) {var out=JSON.createArrayNode();node.forEach(v -> out.add(ordered(v)));return out;}
        return node;
    }
}
