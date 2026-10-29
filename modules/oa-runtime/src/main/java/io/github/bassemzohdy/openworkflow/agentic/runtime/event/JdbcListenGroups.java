package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.event.InboxListenGroup.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** Retained input selection/order only. No method returns callback permission, consumption proof or an ACK. */
@ApplicationScoped @Transactional(Transactional.TxType.NOT_SUPPORTED)
public class JdbcListenGroups {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Subject RECOVERY=new Subject(SubjectKind.SERVICE,"system:event-recovery");
    private final DataSource data;
    private final JdbcInboxTaskLease leases;
    private final AuditSink audit;
    private final Clock clock;
    public record Binding(Instant deadline,long lastSequence,boolean captureComplete) { }
    public record Registered(InboxListenGroup group,String configurationSnapshot,Duration timeout,Binding binding) { }
    /** Historical selection attribution; this is explicitly not a current owner lease or a dispatch reservation. */
    public record Recorded(long sequence,Disposition disposition,List<Slot> memberships,InboxMessage receipt,String selectingOwner,long selectingEpoch) {
        public Recorded {memberships=List.copyOf(memberships);}
    }
    private record Row(Binding binding) { }
    @Inject public JdbcListenGroups(DataSource data,JdbcInboxTaskLease leases,AuditTrail audit) {this(data,leases,audit,Clock.systemUTC());}
    public JdbcListenGroups(DataSource data,JdbcInboxTaskLease leases,AuditSink audit,Clock clock) {
        this.data=Objects.requireNonNull(data);this.leases=Objects.requireNonNull(leases);this.audit=Objects.requireNonNull(audit);this.clock=Objects.requireNonNull(clock);
    }

    /** Exact configuration bytes and the first deadline are retained. Retrying never extends or rebinds either. */
    public Binding register(InboxTaskClaim claim,InboxListenGroup group,String snapshot,Duration timeout) {
        sameTask(claim,group);long millis=timeoutMillis(timeout);
        if(!group.configurationFingerprint().equals(InboxListenGroup.snapshotFingerprint(snapshot)))
            throw new IllegalArgumentException("configuration snapshot does not identify the pinned group");
        try {if(!JSON.readTree(snapshot).isObject()) throw new IllegalArgumentException("configuration snapshot must be an object");}
        catch(JsonProcessingException invalid) {throw new IllegalArgumentException("configuration snapshot is invalid",invalid);}
        var binding=Transactions.inTransaction(data,c->{
            leases.requireCurrent(c,claim,true);
            try(var s=statement(c,"""
                    insert into inbox.listen_group(tenant,instance_id,task_digest,group_digest,spec_json,configuration_json,slots_json,
                      timeout_millis,registered_at,deadline,write_owner,write_epoch)
                    select ?,?,?,?,?,?,?::jsonb,?,n,n+?*interval '1 millisecond',?,? from (select clock_timestamp() n) clock
                    on conflict(tenant,instance_id,task_digest) do nothing
                    """)) {
                key(s,group,1);s.setString(4,group.digest());s.setString(5,json(group));s.setString(6,snapshot);s.setString(7,json(allSlots(group)));
                s.setLong(8,millis);s.setLong(9,millis);s.setString(10,claim.owner());s.setLong(11,claim.epoch());s.executeUpdate();
            }
            var row=row(c,group);
            try(var s=statement(c,"select configuration_json,timeout_millis from inbox.listen_group where tenant=? and instance_id=? and task_digest=?")) {
                key(s,group,1);try(var r=s.executeQuery()) {
                    r.next();if(!snapshot.equals(r.getString(1)) || millis!=r.getLong(2)) throw new IllegalStateException("group configuration or deadline contract changed");
                }
            }
            leases.requireCurrent(c,claim,true);return row.binding();
        });
        record(group,null,"event.group.registered");return binding;
    }

    /** Only a selector-produced complete match set for this group and receipt can reach the public store. */
    public Optional<Recorded> offer(InboxTaskClaim claim,InboxListenGroup group,InboxMessage candidate,SdkListenMatchSet.Selection selection) {
        Objects.requireNonNull(selection).require(group,Objects.requireNonNull(candidate));
        return offer(claim,group,candidate,selection.slots());
    }

    // Component-only entry point; production callers cannot construct their own selection.
    Optional<Recorded> offer(InboxTaskClaim claim,InboxListenGroup group,InboxMessage candidate,Set<Slot> matches) {
        sameTask(claim,group);Objects.requireNonNull(candidate);matches=Set.copyOf(matches);var match=group.classify(matches);
        String completeFingerprint=completeFingerprint(group,candidate,matches);
        if(!candidate.tenant().equals(group.task().tenant()) || !candidate.instanceId().equals(group.task().instanceId()))
            throw new IllegalArgumentException("receipt belongs to another registry scope");
        Optional<Recorded> result=Transactions.inTransaction(data,c->{
            leases.requireCurrent(c,claim,true);var row=row(c,group);
            var actual=message(c,group,candidate.source(),candidate.eventId(),true);
            if(!candidate.equals(actual)) throw new IllegalStateException("selected receipt content changed");
            try(var s=statement(c,"select sequence,matched_slots,full_match_fingerprint from inbox.listen_item where tenant=? and instance_id=? and task_digest=? and source=? and event_id=?")) {
                key(s,group,1);s.setString(4,candidate.source());s.setString(5,candidate.eventId());
                try(var r=s.executeQuery()) {if(r.next()) {
                    if(r.getLong(1)>row.binding().lastSequence()) throw new IllegalStateException("selected receipt is not finalized");
                    if(!readSlots(r.getString(2)).equals(match.slots()) || !completeFingerprint.equals(r.getString(3)))
                        throw new IllegalStateException("receipt complete match set changed or predates complete-match retention");
                    var previous=read(c,group,r.getLong(1));leases.requireCurrent(c,claim,true);return Optional.of(previous);
                }}
            }
            if(row.binding().captureComplete() || !row.binding().deadline().isAfter(now(c)) || match.disposition()==Disposition.NONE) return Optional.empty();
            boolean exclusive=match.disposition()==Disposition.UNTIL || group.strategy()!=Strategy.ANY;
            var available=new ArrayList<Slot>();
            for(var slot:match.slots()) {
                boolean used=false;
                if(exclusive) try(var s=statement(c,"select exists(select 1 from inbox.listen_member where tenant=? and instance_id=? and task_digest=? and role=? and ordinal=?)")) {
                    key(s,group,1);s.setString(4,slot.role().name());s.setInt(5,slot.ordinal());
                    try(var r=s.executeQuery()) {r.next();used=r.getBoolean(1);}
                }
                if(!used) available.add(slot);
            }
            if(available.isEmpty()) return Optional.empty();
            long sequence=Math.addExact(row.binding().lastSequence(),1L);
            try(var s=statement(c,"""
                    insert into inbox.listen_item(tenant,instance_id,task_digest,sequence,role,source,event_id,receipt_fingerprint,
                      matched_slots,selecting_owner,selecting_epoch,full_match_fingerprint) values(?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
                    """)) {
                key(s,group,1);s.setLong(4,sequence);s.setString(5,match.disposition().name());s.setString(6,candidate.source());s.setString(7,candidate.eventId());
                s.setString(8,candidate.fingerprint());s.setString(9,json(match.slots()));s.setString(10,claim.owner());s.setLong(11,claim.epoch());s.setString(12,completeFingerprint);s.executeUpdate();
            }
            for(var slot:available) try(var s=statement(c,"""
                    insert into inbox.listen_member(tenant,instance_id,task_digest,sequence,role,ordinal,filter_fingerprint,exclusive_slot)
                    values(?,?,?,?,?,?,?,?)
                    """)) {
                key(s,group,1);s.setLong(4,sequence);s.setString(5,slot.role().name());s.setInt(6,slot.ordinal());s.setString(7,slot.filterFingerprint());
                s.setBoolean(8,exclusive);s.executeUpdate();
            }
            boolean complete=captureComplete(c,group);
            try(var s=statement(c,"update inbox.listen_group set last_sequence=?,capture_complete=?,write_owner=?,write_epoch=? where tenant=? and instance_id=? and task_digest=?")) {
                s.setLong(1,sequence);s.setBoolean(2,complete);s.setString(3,claim.owner());s.setLong(4,claim.epoch());key(s,group,5);s.executeUpdate();
            }
            leases.requireCurrent(c,claim,true);
            return Optional.of(new Recorded(sequence,match.disposition(),available,actual,claim.owner(),claim.epoch()));
        });
        record(group,result.orElse(null),result.isPresent()?"event.group.selected":"event.group.selection.refused");return result;
    }

    private static String completeFingerprint(InboxListenGroup group,InboxMessage receipt,Set<Slot> slots) {
        var ordered=slots.stream().sorted(Comparator.comparing(Slot::role).thenComparingInt(Slot::ordinal)).toList();
        var encoder=new CanonicalEncoder("oa.inbox.complete-match.v1").text(group.digest()).text(receipt.fingerprint()).number(ordered.size());
        ordered.forEach(slot->encoder.text(slot.key()));return CanonicalEncoder.sha256Hex(encoder.toBytes());
    }

    /** Bounded ordered history for whole-group reconstruction. No callback authority is inferred, even for a new epoch. */
    public List<Recorded> selected(InboxTaskClaim claim,InboxListenGroup group,long afterSequence,int limit) {
        sameTask(claim,group);if(afterSequence<0 || limit<1 || limit>128) throw new IllegalArgumentException("selection page must be bounded");
        return Transactions.inTransaction(data,c->{
            leases.requireCurrent(c,claim,false);var current=row(c,group);var found=new ArrayList<Recorded>();
            try(var s=statement(c,"select sequence from inbox.listen_item where tenant=? and instance_id=? and task_digest=? and sequence>? and sequence<=? order by sequence limit ?")) {
                key(s,group,1);s.setLong(4,afterSequence);s.setLong(5,current.binding().lastSequence());s.setInt(6,limit);
                try(var r=s.executeQuery()) {while(r.next()) {found.add(read(c,group,r.getLong(1)));leases.requireCurrent(c,claim,false);}}
            }
            leases.requireCurrent(c,claim,false);return List.copyOf(found);
        });
    }
    /** Recovers the pinned historical configuration for this exact task, without consulting mutable deployment defaults. */
    public Optional<Registered> load(InboxTaskClaim claim) {
        Objects.requireNonNull(claim);
        return Transactions.inTransaction(data,c->{
            leases.requireCurrent(c,claim,false);
            try(var s=statement(c,"select spec_json,configuration_json,timeout_millis from inbox.listen_group where tenant=? and instance_id=? and task_digest=? for update")) {
                s.setString(1,claim.task().tenant());s.setString(2,claim.task().instanceId().value());s.setString(3,claim.task().digest());
                try(var r=s.executeQuery()) {
                    if(!r.next()) return Optional.empty();
                    var group=parse(r.getString(1),InboxListenGroup.class);sameTask(claim,group);
                    String snapshot=r.getString(2);
                    if(!group.configurationFingerprint().equals(InboxListenGroup.snapshotFingerprint(snapshot))) throw new IllegalStateException("retained configuration changed");
                    var result=new Registered(group,snapshot,Duration.ofMillis(r.getLong(3)),row(c,group).binding());
                    leases.requireCurrent(c,claim,false);return Optional.of(result);
                }
            }
        });
    }
    private Recorded read(Connection c,InboxListenGroup group,long sequence) throws SQLException {
        try(var s=statement(c,"select role,source,event_id,receipt_fingerprint,selecting_owner,selecting_epoch,matched_slots from inbox.listen_item where tenant=? and instance_id=? and task_digest=? and sequence=?")) {
            key(s,group,1);s.setLong(4,sequence);try(var r=s.executeQuery()) {
                if(!r.next()) throw new IllegalStateException("retained selection disappeared");
                var receipt=message(c,group,r.getString(2),r.getString(3),false);
                if(!receipt.fingerprint().equals(r.getString(4))) throw new IllegalStateException("retained selection content changed");
                var slots=new ArrayList<Slot>();
                try(var members=statement(c,"select role,ordinal,filter_fingerprint from inbox.listen_member where tenant=? and instance_id=? and task_digest=? and sequence=? order by ordinal")) {
                    key(members,group,1);members.setLong(4,sequence);try(var m=members.executeQuery()) {while(m.next())
                        slots.add(new Slot(group.digest(),Role.valueOf(m.getString(1)),m.getInt(2),m.getString(3)));}
                }
                var selectedMatches=readSlots(r.getString(7));var selection=group.classify(Set.copyOf(selectedMatches));
                var match=group.classify(Set.copyOf(slots));
                if(!selection.slots().equals(selectedMatches) || !selectedMatches.containsAll(slots) || selection.disposition()!=match.disposition())
                    throw new IllegalStateException("membership was not part of the original receipt match set");
                if(!match.slots().equals(slots) || !match.disposition().name().equals(r.getString(1))) throw new IllegalStateException("retained membership changed");
                return new Recorded(sequence,match.disposition(),slots,receipt,r.getString(5),r.getLong(6));
            }
        }
    }
    private boolean captureComplete(Connection c,InboxListenGroup group) throws SQLException {
        if(group.strategy()==Strategy.ANY && group.stop()==Stop.EXPRESSION) return false; // Requires actual ordered item-output proof later.
        boolean data=group.stop()==Stop.NONE;Role role=data?Role.DATA:Role.UNTIL;
        int required=data?(group.strategy()==Strategy.ANY?1:group.slots(role).size()):(group.stop()==Stop.ALL?group.slots(role).size():1);
        try(var s=statement(c,"select count(distinct ordinal) from inbox.listen_member where tenant=? and instance_id=? and task_digest=? and role=?")) {
            key(s,group,1);s.setString(4,role.name());try(var r=s.executeQuery()) {r.next();return r.getInt(1)>=required;}
        }
    }
    private Row row(Connection c,InboxListenGroup group) throws SQLException {
        try(var s=statement(c,"select group_digest,spec_json,slots_json,deadline,last_sequence,capture_complete from inbox.listen_group where tenant=? and instance_id=? and task_digest=? for update")) {
            key(s,group,1);try(var r=s.executeQuery()) {
                if(!r.next()) throw new IllegalStateException("listen group is not registered");
                if(!group.digest().equals(r.getString(1)) || !group.equals(parse(r.getString(2),InboxListenGroup.class)) || !allSlots(group).equals(readSlots(r.getString(3))))
                    throw new IllegalStateException("pinned listen group changed");
                return new Row(new Binding(r.getTimestamp(4).toInstant(),r.getLong(5),r.getBoolean(6)));
            }
        }
    }
    private InboxMessage message(Connection c,InboxListenGroup group,String source,String id,boolean fresh) throws SQLException {
        try(var s=statement(c,"select type,instance_id,correlation_key,data_json,fingerprint,retain_until from inbox.message where tenant=? and source=? and event_id=? for share")) {
            s.setString(1,group.task().tenant());s.setString(2,source);s.setString(3,id);try(var r=s.executeQuery()) {
                if(!r.next()) throw new IllegalStateException("retained receipt is missing");
                var result=new InboxMessage(group.task().tenant(),source,id,r.getString(1),Ulid.parse(r.getString(2)),r.getString(3),r.getString(4));
                if(!group.task().instanceId().equals(result.instanceId()) || !result.fingerprint().equals(r.getString(5))
                        || fresh && !r.getTimestamp(6).toInstant().isAfter(now(c))) throw new IllegalStateException("receipt is outside retained registry scope");
                return result;
            }
        }
    }
    private static List<Slot> allSlots(InboxListenGroup group) {var slots=new ArrayList<>(group.slots(Role.DATA));slots.addAll(group.slots(Role.UNTIL));return List.copyOf(slots);}
    private static void sameTask(InboxTaskClaim claim,InboxListenGroup group) {
        Objects.requireNonNull(claim);Objects.requireNonNull(group);if(!claim.task().equals(group.task())) throw new IllegalArgumentException("claim belongs to another task");
    }
    private static long timeoutMillis(Duration timeout) {
        Objects.requireNonNull(timeout);long millis=timeout.toMillis();
        if(millis<1 || millis>Duration.ofDays(30).toMillis() || !timeout.equals(Duration.ofMillis(millis)))
            throw new IllegalArgumentException("group timeout must be exact milliseconds from 1ms through 30 days");return millis;
    }
    private static String json(Object value) {try{return JSON.writeValueAsString(value);}catch(JsonProcessingException failed){throw new IllegalStateException("listen metadata encoding failed",failed);}}
    private static <T> T parse(String value,Class<T> type) {try{return JSON.readValue(value,type);}catch(JsonProcessingException failed){throw new IllegalStateException("retained listen metadata is invalid",failed);}}
    private static List<Slot> readSlots(String value) {return List.of(parse(value,Slot[].class));}
    private static void key(PreparedStatement s,InboxListenGroup g,int at) throws SQLException {s.setString(at,g.task().tenant());s.setString(at+1,g.task().instanceId().value());s.setString(at+2,g.task().digest());}
    private static PreparedStatement statement(Connection c,String sql) throws SQLException {var s=c.prepareStatement(sql);s.setQueryTimeout(5);return s;}
    private static Instant now(Connection c) throws SQLException {try(var s=statement(c,"select clock_timestamp()");var r=s.executeQuery()){r.next();return r.getTimestamp(1).toInstant();}}
    private void record(InboxListenGroup group,Recorded selection,String action) {
        var values=new LinkedHashMap<String,String>();values.put(AuditKeys.TENANT,group.task().tenant());values.put(AuditKeys.INSTANCE,group.task().instanceId().value());
        values.put("event.group",group.digest());values.put("event.configuration",group.configurationFingerprint());
        if(selection!=null) {values.put("event.receipt",selection.receipt().fingerprint());values.put("event.sequence",Long.toString(selection.sequence()));}
        audit.record(AuditDraft.of(UlidGenerator.shared().next(),clock.instant(),RECOVERY,action,"event-group",group.digest(),values));
    }
}
