package io.github.bassemzohdy.openworkflow.agentic.contracts.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Immutable logical registration shape of one listen occurrence. Filter hashes retain AST order and duplicate slots;
 * configurationFingerprint pins the complete listen/read/foreach definition and approved producer/correlation snapshot.
 * Neither this data nor classify grants
 * authority, ownership, callback permission, consumption proof or a durable winner. A trusted selector supplies matches.
 */
public record InboxListenGroup(InboxTask task,Strategy strategy,List<String> dataFilters,Stop stop,
                               List<String> stopFilters,String expressionFingerprint,String configurationFingerprint) {
    public static final int MAX_SLOTS=128;
    private static final Pattern SHA256=Pattern.compile("[0-9a-f]{64}");
    private static final String WILDCARD=CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.filter.wildcard.v1").toBytes());
    public enum Strategy {ONE,ANY,ALL}
    public enum Stop {NONE,EXPRESSION,ONE,ANY,ALL}
    public enum Role {DATA,UNTIL}
    public enum Disposition {NONE,DATA,UNTIL}

    public InboxListenGroup {
        Objects.requireNonNull(task);Objects.requireNonNull(strategy);Objects.requireNonNull(stop);
        dataFilters=filters(dataFilters);stopFilters=filters(stopFilters);
        configurationFingerprint=hash("configuration fingerprint",configurationFingerprint);
        if(strategy==Strategy.ONE && dataFilters.size()!=1 || strategy==Strategy.ALL && dataFilters.isEmpty())
            throw new IllegalArgumentException("one requires one data filter; all requires at least one");
        if(strategy!=Strategy.ANY && stop!=Stop.NONE) throw new IllegalArgumentException("until requires any strategy");
        if(stop==Stop.NONE || stop==Stop.EXPRESSION) {
            if(!stopFilters.isEmpty()) throw new IllegalArgumentException("non-event until has no filter registrations");
        } else if(stop==Stop.ONE && stopFilters.size()!=1 || stop==Stop.ALL && stopFilters.isEmpty())
            throw new IllegalArgumentException("until one requires one filter; until all requires at least one");
        if(stop==Stop.EXPRESSION) expressionFingerprint=hash("until expression fingerprint",expressionFingerprint);
        else if(expressionFingerprint!=null) throw new IllegalArgumentException("only expression until has an expression fingerprint");
        int stopCount=stop==Stop.NONE || stop==Stop.EXPRESSION?0:Math.max(1,stopFilters.size());
        if(Math.max(1,dataFilters.size())+stopCount>MAX_SLOTS) throw new IllegalArgumentException("listen group exceeds registration bound");
    }
    private static List<String> filters(List<String> values) {
        var result=Checks.copyList("filter fingerprints",values,MAX_SLOTS);
        result.forEach(v->hash("filter fingerprint",v));return result;
    }
    private static String hash(String field,String value) {return Checks.requireMatching(field,value,SHA256);}
    public String digest() {
        var value=new CanonicalEncoder("oa.inbox.group.v1").text(task.digest()).text(strategy.name()).text(stop.name())
                .text(configurationFingerprint).optionalText(expressionFingerprint).number(dataFilters.size());
        dataFilters.forEach(value::text);value.number(stopFilters.size());stopFilters.forEach(value::text);
        return CanonicalEncoder.sha256Hex(value.toBytes());
    }
    public String key() {return "ig1-"+digest();}

    /** ANY [] has one logical wildcard slot, regardless of the broker's number of physical registrations. */
    public List<Slot> slots(Role role) {
        Objects.requireNonNull(role);String group=digest();
        if(role==Role.UNTIL && (stop==Stop.NONE || stop==Stop.EXPRESSION)) return List.of();
        List<String> filters=role==Role.DATA?dataFilters:stopFilters;
        if(filters.isEmpty()) return List.of(new Slot(group,role,0,WILDCARD));
        var result=new ArrayList<Slot>(filters.size());
        for(int i=0;i<filters.size();i++) result.add(new Slot(group,role,i,filters.get(i)));
        return List.copyOf(result);
    }

    /**
     * Classifies all validated matches of ONE receipt before any callback. Until takes precedence over data even for
     * ANY []; ALL retains each distinct logical membership, ANY chooses the first matching AST slot. No state changes.
     */
    public Match classify(Set<Slot> matches) {
        Objects.requireNonNull(matches);
        if(matches.size()>MAX_SLOTS) throw new IllegalArgumentException("receipt matches exceed registration bound");
        var data=slots(Role.DATA);var until=slots(Role.UNTIL);
        for(var slot:matches) {
            Objects.requireNonNull(slot);
            if(!(slot.role()==Role.DATA?data:until).contains(slot))
                throw new IllegalArgumentException("match does not identify this pinned group registration");
        }
        var stops=until.stream().filter(matches::contains).toList();
        if(!stops.isEmpty()) return new Match(Disposition.UNTIL,stop==Stop.ALL?stops:List.of(stops.getFirst()));
        var consumed=data.stream().filter(matches::contains).toList();
        if(consumed.isEmpty()) return new Match(Disposition.NONE,List.of());
        return new Match(Disposition.DATA,strategy==Strategy.ALL?consumed:List.of(consumed.getFirst()));
    }
    public record Slot(String groupDigest,Role role,int ordinal,String filterFingerprint) {
        public Slot {
            groupDigest=hash("group digest",groupDigest);Objects.requireNonNull(role);
            Checks.requireInRange("filter ordinal",ordinal,0,MAX_SLOTS-1);filterFingerprint=hash("filter fingerprint",filterFingerprint);
        }
        public String key() {return "ir1-"+CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.inbox.registration.v1")
                .text(groupDigest).text(role.name()).number(ordinal).text(filterFingerprint).toBytes());}
    }
    /** Selection data only. The store must atomically fence and persist receipt/winner/order/proof before releasing work. */
    public record Match(Disposition disposition,List<Slot> slots) {
        public Match {
            Objects.requireNonNull(disposition);slots=Checks.copyList("matched slots",slots,MAX_SLOTS);
            if((disposition==Disposition.NONE)!=slots.isEmpty()) throw new IllegalArgumentException("match disposition and memberships disagree");
            if(!slots.isEmpty()) {
                Role expected=disposition==Disposition.DATA?Role.DATA:Role.UNTIL;int previous=-1;
                String group=slots.getFirst().groupDigest();
                for(var slot:slots) {
                    if(slot.role()!=expected || !slot.groupDigest().equals(group) || slot.ordinal()<=previous)
                        throw new IllegalArgumentException("match memberships must be ordered distinct slots of one group and role");
                    previous=slot.ordinal();
                }
            }
        }
    }
}
