package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import com.fasterxml.jackson.databind.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Opt-in atomic producer, not a central append, delivery receipt or dispatch guard. The supplied connection belongs
 * to the ledger's short transaction. No I/O to an audit service or business target occurs here. New root provenance
 * requires the ledger's known fresh INSERT; existing unproven legacy rows are never upgraded/guessed from ws1.
 * Keep the original source/draft id/time/content on every readback, including after an uncertain actual commit reply.
 * V75/V77 are applied; production adoption requires reviewed consumers and a mandatory receipt/current-authority target boundary.
 */
public final class EffectAuditProducer {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Subject ACTOR=new Subject(SubjectKind.SERVICE,"system:effect-ledger");
    private static final String WHERE=" where thread_name=? and node_id=? and idempotency_key=?";
    record Fact(long id,Instant occurredAt,EffectKey key,EffectState from,EffectState to,String reason) { }
    record Origin(EffectScope scope,Long parentInitialId) { }
    record Bound(EffectScope scope,long initialId,Long parentInitialId) { }
    public record Retained(AuditSource source,AuditEvent draft) {
        public Retained {
            Objects.requireNonNull(source);Objects.requireNonNull(draft);
            var attrs=draft.attributes();
            if(!source.contentFingerprint().equals(AuditDraftFingerprint.of(draft)) || !source.key().equals(attrs.get("effect.source"))
                    || !source.scopeFingerprint().equals(attrs.get("effect.scope")) || !source.keyFingerprint().equals(attrs.get("effect.key"))
                    || !source.transition().name().equals(attrs.get("effect.transition")) || !Long.toString(source.sequence()).equals(attrs.get("effect.journal"))
                    || !ACTOR.equals(draft.actor()) || !draft.action().equals("effect."+source.transition().name().toLowerCase(Locale.ROOT))
                    || !draft.resourceType().equals("effect") || !draft.resourceId().equals("efp1-"+source.keyFingerprint())) throw inconsistent();
        }
    }

    /** Structural current-registry check only; verified caller/grant/PDP/actual task/target authority is separate. */
    void lockRoot(Connection c,EffectScope scope) throws SQLException {
        transaction(c);
        try(var q=c.prepareStatement("select tenant,owner_kind,owner_id,status,wait_deadline from task.instance where id=? for update")) {
            q.setString(1,scope.instanceId().value());
            try(var r=q.executeQuery()) {
                if(!r.next() || !scope.tenant().equals(r.getString(1)) || !scope.owner().kind().name().equals(r.getString(2))
                        || !scope.owner().id().equals(r.getString(3)) || !Set.of("RUNNING","WAITING").contains(r.getString(4))) throw inconsistent();
                var deadline=r.getTimestamp(5);
                // Database clock is read AFTER the row-lock wait, never from a stale SELECT expression.
                if(deadline!=null) try(var clock=c.createStatement();var now=clock.executeQuery("select clock_timestamp()")) {
                    now.next();if(!deadline.toInstant().isAfter(now.getTimestamp(1).toInstant())) throw inconsistent();
                }
            }
        }
    }

    Optional<Bound> bound(Connection c,EffectKey key) throws SQLException {
        try(var q=c.prepareStatement("select tenant,registry_instance_id,owner_kind,owner_id,scope_fingerprint,initial_event_id,parent_initial_event_id from effect.audit_scope"+WHERE)) {
            bind(q,key);try(var r=q.executeQuery()) {
                if(!r.next()) return Optional.empty();
                var scope=new EffectScope(r.getString(1),Ulid.parse(r.getString(2)),new Subject(SubjectKind.valueOf(r.getString(3)),r.getString(4)));
                scope.requireKey(key);long initial=r.getLong(6);
                if(initial<1 || !scopeFingerprint(scope).equals(r.getString(5))) throw inconsistent();
                return Optional.of(new Bound(scope,initial,r.getObject(7,Long.class)));
            }
        }
    }
    Origin compensationOrigin(Connection c,EffectKey original) throws SQLException {
        return bound(c,original).map(b->new Origin(b.scope(),b.initialId())).orElse(null);
    }
    void requireOrigin(Connection c,EffectKey key,Origin origin) throws SQLException {
        var stored=bound(c,key).orElseThrow(EffectAuditProducer::inconsistent);
        if(!stored.scope().equals(origin.scope()) || !Objects.equals(stored.parentInitialId(),origin.parentInitialId())) throw inconsistent();
        // An exact prepare retry must verify its original retained source, including uncertain-commit recovery.
        find(c,stored.initialId()).orElseThrow(EffectAuditProducer::inconsistent);
    }
    /** Called ONLY after the ledger observed inserted==1 in this SAME transaction. Never a historical backfill API. */
    void bindFresh(Connection c,Fact initial,Origin origin) throws SQLException {
        transaction(c);origin.scope().requireKey(initial.key());
        if(initial.from()!=null || initial.to()!=EffectState.INTENDED || !initial.reason().equals("effect.intended")) throw inconsistent();
        try(var q=c.prepareStatement("""
                insert into effect.audit_scope(thread_name,node_id,idempotency_key,tenant,registry_instance_id,
                  owner_kind,owner_id,scope_fingerprint,initial_event_id,parent_initial_event_id) values(?,?,?,?,?,?,?,?,?,?)
                """)) {
            bind(q,initial.key());var s=origin.scope();q.setString(4,s.tenant());q.setString(5,s.instanceId().value());
            q.setString(6,s.owner().kind().name());q.setString(7,s.owner().id());q.setString(8,scopeFingerprint(s));
            q.setLong(9,initial.id());q.setObject(10,origin.parentInitialId());q.executeUpdate();
        }
    }

    /** Scoped facts retain ONE draft atomically. Legacy facts stay legacy; this method never invents their owner. */
    void capture(Connection c,Fact fact) throws SQLException {
        transaction(c);var scope=bound(c,fact.key());if(scope.isEmpty()) return;
        var old=find(c,fact.id());if(old.isPresent()) return; // find verifies exact retained fact/scope/content.
        String purpose,target,request;
        try(var q=c.prepareStatement("select purpose,target,request_fingerprint from effect.ledger"+WHERE)) {
            bind(q,fact.key());try(var r=q.executeQuery()){if(!r.next())throw inconsistent();purpose=r.getString(1);target=r.getString(2);request=r.getString(3);}
        }
        var value=build(scope.get().scope(),fact,purpose,target,request,UlidGenerator.shared().next());
        try(var q=c.prepareStatement("""
                insert into effect.audit_source(source_event_id,scope_initial_event_id,schema_version,scope_fingerprint,
                  key_fingerprint,transition,source_sequence,source_key,draft_id,occurred_at,actor_kind,actor_id,
                  action,resource_type,resource_id,attributes,content_fingerprint)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """)) {
            var source=value.source();var draft=value.draft();q.setLong(1,fact.id());q.setLong(2,scope.get().initialId());q.setInt(3,source.schemaVersion());
            q.setString(4,source.scopeFingerprint());q.setString(5,source.keyFingerprint());q.setString(6,source.transition().name());
            q.setLong(7,source.sequence());q.setString(8,source.key());q.setString(9,draft.id().value());q.setTimestamp(10,Timestamp.from(draft.occurredAt()));
            q.setString(11,draft.actor().kind().name());q.setString(12,draft.actor().id());q.setString(13,draft.action());
            q.setString(14,draft.resourceType());q.setString(15,draft.resourceId());q.setString(16,json(draft.attributes()));
            q.setString(17,source.contentFingerprint());q.executeUpdate();
        }
    }

    /** Historical DATA only. Only an absent source is empty; broken journal/ledger anchors refuse. No permission follows. */
    public Optional<Retained> find(Connection c,long journalId) throws SQLException {
        if(journalId<1) throw new IllegalArgumentException("journal id must be positive");
        try(var q=c.prepareStatement("""
                select a.*,e.thread_name as journal_thread,e.node_id as journal_node,e.idempotency_key as journal_key,
                  e.from_state as journal_from,e.to_state as journal_to,e.reason as journal_reason,e.occurred_at as journal_time,
                  l.purpose as ledger_purpose,l.target as ledger_target,l.request_fingerprint as ledger_request
                from effect.audit_source a left join effect.event e on e.id=a.source_event_id
                left join effect.ledger l on l.thread_name=e.thread_name and l.node_id=e.node_id and l.idempotency_key=e.idempotency_key
                where a.source_event_id=?
                """)) {
            q.setLong(1,journalId);try(var r=q.executeQuery()) {
                if(!r.next()) return Optional.empty();
                if(r.getString("journal_thread")==null || r.getString("ledger_purpose")==null) throw inconsistent();
                var key=new EffectKey(ThreadId.parse(r.getString("journal_thread")),r.getString("journal_node"),r.getString("journal_key"));
                var scope=bound(c,key).orElseThrow(EffectAuditProducer::inconsistent);
                if(scope.initialId()!=r.getLong("scope_initial_event_id")) throw inconsistent();
                var fact=new Fact(journalId,r.getTimestamp("journal_time").toInstant(),key,state(r.getString("journal_from")),
                        EffectState.valueOf(r.getString("journal_to")),r.getString("journal_reason"));
                var expected=build(scope.scope(),fact,r.getString("ledger_purpose"),r.getString("ledger_target"),r.getString("ledger_request"),Ulid.parse(r.getString("draft_id")));
                var source=new AuditSource(r.getInt("schema_version"),r.getString("scope_fingerprint"),r.getString("key_fingerprint"),
                        AuditSource.Transition.valueOf(r.getString("transition")),r.getLong("source_sequence"),r.getString("content_fingerprint"));
                var draft=new AuditEvent(Ulid.parse(r.getString("draft_id")),1,r.getTimestamp("occurred_at").toInstant(),
                        new Subject(SubjectKind.valueOf(r.getString("actor_kind")),r.getString("actor_id")),r.getString("action"),
                        r.getString("resource_type"),r.getString("resource_id"),attributes(r.getString("attributes")),AuditHash.GENESIS,AuditHash.GENESIS);
                var retained=new Retained(source,draft);
                if(!expected.equals(retained) || !source.key().equals(r.getString("source_key"))) throw inconsistent();
                return Optional.of(retained);
            }
        }
    }

    /** Bounded DATA page, not a complete-history assertion, global watermark or proof of central append/permission. */
    public List<Retained> forKey(Connection c,EffectKey key,long afterJournalId,int limit) throws SQLException {
        Objects.requireNonNull(key);keyFingerprint(key);
        if(afterJournalId<0 || limit<1 || limit>128) throw new IllegalArgumentException("effect source page must be bounded");
        var ids=new ArrayList<Long>();
        try(var q=c.prepareStatement("""
                select a.source_event_id from effect.audit_source a
                left join effect.event e on e.id=a.source_event_id
                left join effect.audit_scope s on s.initial_event_id=a.scope_initial_event_id
                where ((e.thread_name=? and e.node_id=? and e.idempotency_key=?)
                    or (s.thread_name=? and s.node_id=? and s.idempotency_key=?)
                    or (e.id is null and s.initial_event_id is null and a.key_fingerprint=?))
                  and a.source_event_id>? order by a.source_event_id limit ?
                """)) {
            bind(q,key);q.setString(4,key.threadId().value());q.setString(5,key.nodeId());q.setString(6,key.idempotencyKey());
            q.setString(7,keyFingerprint(key));q.setLong(8,afterJournalId);q.setInt(9,limit);
            try(var r=q.executeQuery()){while(r.next())ids.add(r.getLong(1));}
        }
        var result=new ArrayList<Retained>();for(long id:ids)result.add(find(c,id).orElseThrow(EffectAuditProducer::inconsistent));return List.copyOf(result);
    }

    static Retained build(EffectScope scope,Fact fact,String purpose,String target,String request,Ulid draftId) {
        if(fact.id()<1 || fact.occurredAt().getNano()%1000!=0 || !Set.of("EFFECT","COMPENSATION").contains(purpose)
                || request==null || !request.matches("[0-9a-f]{64}")) throw inconsistent();
        scope.requireKey(fact.key());var transition=transition(fact);String sf=scopeFingerprint(scope),kf=keyFingerprint(fact.key());
        var identity=new AuditSource(1,sf,kf,transition,fact.id(),"0".repeat(64));
        var attrs=new TreeMap<String,String>();attrs.put("oa.chain","effect-"+fingerprint("oa.effect.audit-chain.v1",scope.tenant()));
        attrs.put("oa.tenant",scope.tenant());attrs.put("oa.instance",scope.instanceId().value());
        attrs.put("effect.owner.kind",scope.owner().kind().name());attrs.put("effect.owner.id",scope.owner().id());
        attrs.put("effect.scope",sf);attrs.put("effect.key",kf);attrs.put("effect.source",identity.key());attrs.put("effect.journal",Long.toString(fact.id()));
        attrs.put("effect.transition",transition.name());attrs.put("effect.from",fact.from()==null?"NONE":fact.from().name());attrs.put("effect.to",fact.to().name());
        attrs.put("effect.purpose",purpose);attrs.put("effect.target",fingerprint("oa.effect.audit-target.v1",target));attrs.put("effect.request",request);
        var draft=new AuditEvent(draftId,1,fact.occurredAt(),ACTOR,"effect."+transition.name().toLowerCase(Locale.ROOT),
                "effect","efp1-"+kf,attrs,AuditHash.GENESIS,AuditHash.GENESIS);
        return new Retained(new AuditSource(1,sf,kf,transition,fact.id(),AuditDraftFingerprint.of(draft)),draft);
    }
    private static AuditSource.Transition transition(Fact f) {
        if(f.reason().equals("effect.intended") && f.from()==null && f.to()==EffectState.INTENDED)return AuditSource.Transition.INTENT;
        if(f.reason().equals("effect.dispatch") && f.from()==EffectState.INTENDED && f.to()==EffectState.INTENDED)return AuditSource.Transition.DISPATCH;
        if(f.reason().equals("effect.outcome") && f.from()==EffectState.INTENDED && (f.to()==EffectState.CONFIRMED || f.to()==EffectState.FAILED))return AuditSource.Transition.OUTCOME;
        if(f.reason().equals("effect.outcome") && (f.from()==EffectState.CONFIRMED || f.from()==EffectState.FAILED) && f.to()==EffectState.COMPENSATED)return AuditSource.Transition.COMPENSATION;
        if(f.reason().equals("effect.outcome.unknown") && f.from()==EffectState.INTENDED && f.to()==EffectState.INTENDED)return AuditSource.Transition.UNKNOWN;
        if(f.reason().equals("effect.compensation.unresolved") && (f.from()==EffectState.CONFIRMED || f.from()==EffectState.FAILED) && f.from()==f.to())return AuditSource.Transition.UNKNOWN;
        throw new IllegalStateException("unsupported scoped effect journal fact");
    }
    private static EffectState state(String value) {return value==null?null:EffectState.valueOf(value);}
    private static String scopeFingerprint(EffectScope s) {return fingerprint("oa.effect.audit-scope.v1",s.tenant(),s.instanceId().value(),s.owner().kind().name(),s.owner().id());}
    private static String keyFingerprint(EffectKey k) {return fingerprint("oa.effect.audit-key.v1",k.threadId().value(),k.nodeId(),k.idempotencyKey());}
    private static String fingerprint(String domain,String... fields) {var e=new CanonicalEncoder(domain);for(String f:fields)e.text(f);return CanonicalEncoder.sha256Hex(e.toBytes());}
    private static void bind(PreparedStatement q,EffectKey key) throws SQLException {q.setString(1,key.threadId().value());q.setString(2,key.nodeId());q.setString(3,key.idempotencyKey());}
    private static void transaction(Connection c) throws SQLException {if(c.getAutoCommit())throw new IllegalStateException("effect source requires the journal transaction");}
    private static IllegalStateException inconsistent() {return new IllegalStateException("retained effect audit provenance or content differs");}
    private static String json(Map<String,String> attrs) {
        try{return JSON.writeValueAsString(attrs);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw inconsistent();}
    }
    private static Map<String,String> attributes(String text) {
        if(text==null || text.length()>65536)throw inconsistent();
        try {
            var node=JSON.readTree(text);if(!node.isObject() || node.size()>AuditEvent.MAX_ATTRIBUTES)throw inconsistent();
            var attrs=new TreeMap<String,String>();var fields=node.fields();while(fields.hasNext()) {
                var e=fields.next();if(!e.getValue().isTextual())throw inconsistent();attrs.put(e.getKey(),e.getValue().textValue());
            }
            return attrs;
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw inconsistent();}
    }
}
