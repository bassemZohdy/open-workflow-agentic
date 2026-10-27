package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditDraft;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep;
import io.github.bassemzohdy.openworkflow.agentic.runtime.policy.PolicyDenied;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;

/** Explicitly constructed, opt-in V80 proposal consumer. It is not CDI registered and opens no public route. */
public final class ManualEffectResolver {
    private static final Set<String> REQUIRED=Set.of("oa.recover");
    private static final Set<String> REASONS=Set.of("TARGET_CONFIRMED","TARGET_REJECTED","OPERATOR_VERIFIED");
    private final DataSource dataSource;
    private final TransactionManager transactions;
    private final Pep pep;
    private final AuditAppendOnce audit;
    private final JdbcEffectLedger ledger;

    public ManualEffectResolver(DataSource dataSource,TransactionManager transactions,Pep pep,
                                AuditAppendOnce audit,JdbcEffectLedger ledger) {
        this.dataSource=Objects.requireNonNull(dataSource);this.transactions=Objects.requireNonNull(transactions);
        this.pep=Objects.requireNonNull(pep);this.audit=Objects.requireNonNull(audit);this.ledger=Objects.requireNonNull(ledger);
    }

    /** Repeated calls must reuse requestId and exactly match desired state and finite reason code. */
    public Effect resolve(CallerAuthority authority,EffectKey key,Ulid requestId,EffectState desired,String reasonCode) {
        Objects.requireNonNull(authority);Objects.requireNonNull(key);Objects.requireNonNull(requestId);
        if(desired!=EffectState.CONFIRMED&&desired!=EffectState.FAILED)throw new IllegalArgumentException("manual outcome must be CONFIRMED or FAILED");
        if(!REASONS.contains(reasonCode))throw new IllegalArgumentException("unsupported manual resolution reason");
        var caller=authority.caller();
        if(caller.subject()==null||caller.subject().kind()!=SubjectKind.USER)throw new IllegalArgumentException("manual resolution requires a verified user");
        authority.requireCurrent(REQUIRED);
        var initial=decide(authority,key);
        if(!initial.allows())throw new PolicyDenied(initial);
        DecisionRow row=Transactions.inSuspendedTransaction(transactions,()->Transactions.inTransaction(dataSource,c->retain(c,authority,key,requestId,desired,reasonCode,initial)));
        authority.requireCurrent(REQUIRED);
        var beforeAppend=decide(authority,key);
        if(!beforeAppend.allows())throw new PolicyDenied(beforeAppend);
        authority.requireCurrent(REQUIRED);
        var draft=manualDraft(row,key);
        AuditReceipt receipt=audit.appendOnce(row.source(),draft);receipt.requireMatches(row.source(),draft);
        authority.requireCurrent(REQUIRED);
        var finalDecision=decide(authority,key);
        if(!finalDecision.allows())throw new PolicyDenied(finalDecision);
        return ledger.applyManualDecision(row.id(),key,desired,()->authority.requireCurrent(REQUIRED),()->decide(authority,key));
    }

    private Decision decide(CallerAuthority authority,EffectKey key) {
        return pep.instanceOperation(Pep.InstanceOperation.RESOLVE_EFFECT,authority.caller().subject(),authority.caller().scopes(),
                instanceId(key));
    }

    private Ulid instanceId(EffectKey key) {
        return Transactions.inSuspendedTransaction(transactions,()->Transactions.inTransaction(dataSource,c->{
            try(var q=c.prepareStatement("select registry_instance_id from effect.audit_scope where thread_name=? and node_id=? and idempotency_key=?")) {
                bind(q,key);try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("effect has no retained verified scope");return Ulid.parse(r.getString(1));}
            }
        }));
    }

    private DecisionRow retain(Connection c,CallerAuthority authority,EffectKey key,Ulid requestId,EffectState desired,String reason,Decision decision) throws SQLException {
        var caller=authority.caller();
        boolean prior;
        try(var q=c.prepareStatement("""
            select s.tenant,s.registry_instance_id,s.owner_kind,s.owner_id,s.scope_fingerprint,l.state,l.attempted,l.request_fingerprint
            from effect.audit_scope s join effect.ledger l using(thread_name,node_id,idempotency_key)
            where s.thread_name=? and s.node_id=? and s.idempotency_key=? for update of s,l
            """)) {
            bind(q,key);try(var r=q.executeQuery()) {
                if(!r.next()||!caller.tenant().equals(r.getString(1))||r.getString(8)==null)
                    throw new IllegalStateException("effect has no matching retained verified caller scope");
                prior=hasRequest(c,requestId,key,caller.subject(),desired,reason);
                if(!prior&&(!"INTENDED".equals(r.getString(6))||!r.getBoolean(7)||!hasUnknown(c,key)))
                    throw new IllegalStateException("effect is not an attempted unresolved effect with an unknown outcome");
            }
        }
        long id;try(var q=c.prepareStatement("select nextval(pg_get_serial_sequence('effect.manual_decision','decision_id'))")) {try(var r=q.executeQuery()){r.next();id=r.getLong(1);}}
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);Ulid draftId=UlidGenerator.shared().next();
        String tenant,instance,ownerKind,ownerId,storedScope;
        try(var q=c.prepareStatement("select s.tenant,s.registry_instance_id,s.owner_kind,s.owner_id from effect.audit_scope s where s.thread_name=? and s.node_id=? and s.idempotency_key=?")) {
            bind(q,key);try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("effect scope disappeared");tenant=r.getString(1);instance=r.getString(2);ownerKind=r.getString(3);ownerId=r.getString(4);}
        }
        var actor=caller.subject();String sf=scopeFingerprint(tenant,instance,ownerKind,ownerId),kf=keyFingerprint(key);
        try(var q=c.prepareStatement("select scope_fingerprint from effect.audit_scope where thread_name=? and node_id=? and idempotency_key=?")) {
            bind(q,key);try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("effect scope disappeared");storedScope=r.getString(1);}
        }
        if(!sf.equals(storedScope))throw new IllegalStateException("effect scope fingerprint differs from retained provenance");
        decision=new Decision(decision.decidedBy(),decision.outcome(),decision.reason(),decision.policy(),decision.decidedAt().truncatedTo(ChronoUnit.MICROS));
        Map<String,String> attrs=attributes(tenant,instance,ownerKind,ownerId,sf,kf,id,actor,desired,reason,decision);
        var draft=AuditDraft.of(draftId,now,actor,"effect.manual-resolution","effect","efp1-"+kf,attrs);
        var source=new AuditSource(1,sf,kf,AuditSource.Transition.MANUAL,id,AuditDraftFingerprint.of(draft));
        if(!prior)try(var q=c.prepareStatement("""
            insert into effect.manual_decision(decision_id,request_id,thread_name,node_id,idempotency_key,tenant,registry_instance_id,
                owner_kind,owner_id,actor_kind,actor_id,desired_state,reason_code,policy_decided_by,policy_outcome,policy_id,
                policy_version,policy_decided_at,source_key,source_scope_fingerprint,source_key_fingerprint,source_content_fingerprint,draft_id,occurred_at)
            overriding system value values(?,?,?,?,?,?,?,?,?,'USER',?,?,?,?,'ALLOW',?,?,?,?,?,?,?,?,?) on conflict(request_id) do nothing
            """)) {
            int i=1;q.setLong(i++,id);q.setString(i++,requestId.value());bind(q,key,i);i+=3;q.setString(i++,tenant);q.setString(i++,instance);
            q.setString(i++,ownerKind);q.setString(i++,ownerId);q.setString(i++,actor.id());q.setString(i++,desired.name());q.setString(i++,reason);
            q.setString(i++,decision.decidedBy());q.setString(i++,decision.policy().policyId());q.setString(i++,decision.policy().policySetVersion());
            q.setTimestamp(i++,Timestamp.from(decision.decidedAt()));q.setString(i++,source.key());q.setString(i++,sf);q.setString(i++,kf);
            q.setString(i++,source.contentFingerprint());q.setString(i++,draftId.value());q.setTimestamp(i,Timestamp.from(now));q.executeUpdate();
        }
        try(var q=c.prepareStatement("select decision_id,actor_kind,actor_id,desired_state,reason_code,source_key,source_scope_fingerprint,source_key_fingerprint,source_content_fingerprint,draft_id,occurred_at,policy_decided_by,policy_id,policy_version,policy_decided_at from effect.manual_decision where request_id=?")) {
            q.setString(1,requestId.value());try(var r=q.executeQuery()) {
                if(!r.next()||!"USER".equals(r.getString(2))||!actor.id().equals(r.getString(3))||!desired.name().equals(r.getString(4))||!reason.equals(r.getString(5)))
                    throw new IllegalStateException("manual decision request id was already used with different content");
                var storedDecision=new Decision(r.getString(12),DecisionOutcome.ALLOW,"retained",new PolicyRef(r.getString(13),r.getString(14)),r.getTimestamp(15).toInstant());
                var storedAttrs=attributes(tenant,instance,ownerKind,ownerId,r.getString(7),r.getString(8),r.getLong(1),actor,desired,reason,storedDecision);
                var storedDraft=AuditDraft.of(Ulid.parse(r.getString(10)),r.getTimestamp(11).toInstant(),actor,"effect.manual-resolution","effect","efp1-"+r.getString(8),storedAttrs);
                var storedSource=new AuditSource(1,r.getString(7),r.getString(8),AuditSource.Transition.MANUAL,r.getLong(1),r.getString(9));
                if(!storedSource.key().equals(r.getString(6))||!storedSource.contentFingerprint().equals(AuditDraftFingerprint.of(storedDraft)))throw new IllegalStateException("retained manual decision is inconsistent");
                return new DecisionRow(r.getLong(1),storedSource,storedDraft);
            }
        }
    }

    private static Map<String,String> attributes(String tenant,String instance,String ownerKind,String ownerId,String sf,String kf,long id,
            Subject actor,EffectState desired,String reason,Decision decision) {
        var a=new TreeMap<String,String>();a.put("oa.chain","effect-"+fingerprint("oa.effect.audit-chain.v1",tenant));a.put("oa.tenant",tenant);a.put("oa.instance",instance);
        a.put("effect.owner.kind",ownerKind);a.put("effect.owner.id",ownerId);a.put("effect.scope",sf);a.put("effect.key",kf);
        a.put("effect.transition","MANUAL");a.put("effect.decision",Long.toString(id));a.put("effect.actor.kind",actor.kind().name());a.put("effect.actor.id",actor.id());
        a.put("effect.to",desired.name());a.put("effect.reason",reason);a.put("effect.policy.decidedBy",decision.decidedBy());a.put("effect.policy.outcome",decision.outcome().name());
        a.put("effect.policy.id",decision.policy().policyId());a.put("effect.policy.version",decision.policy().policySetVersion());
        a.put("effect.policy.decidedAt",decision.decidedAt().toString());return a;
    }
    private static AuditEvent manualDraft(DecisionRow row,EffectKey key){return row.draft();}
    private static String scopeFingerprint(String tenant,String instance,String kind,String owner){return fingerprint("oa.effect.audit-scope.v1",tenant,instance,kind,owner);}
    private static String keyFingerprint(EffectKey key){return fingerprint("oa.effect.audit-key.v1",key.threadId().value(),key.nodeId(),key.idempotencyKey());}
    private static String fingerprint(String domain,String... parts){var e=new CanonicalEncoder(domain);for(String p:parts)e.text(p);return CanonicalEncoder.sha256Hex(e.toBytes());}
    private static void bind(PreparedStatement q,EffectKey k)throws SQLException{bind(q,k,1);}
    private static void bind(PreparedStatement q,EffectKey k,int offset)throws SQLException{q.setString(offset,k.threadId().value());q.setString(offset+1,k.nodeId());q.setString(offset+2,k.idempotencyKey());}
    private static boolean hasUnknown(Connection c,EffectKey k)throws SQLException{try(var q=c.prepareStatement("select 1 from effect.event where thread_name=? and node_id=? and idempotency_key=? and reason='effect.outcome.unknown' and from_state='INTENDED' and to_state='INTENDED' limit 1")){bind(q,k);try(var r=q.executeQuery()){return r.next();}}}
    private static boolean hasRequest(Connection c,Ulid request,EffectKey key,Subject actor,EffectState desired,String reason)throws SQLException {
        try(var q=c.prepareStatement("select thread_name,node_id,idempotency_key,actor_kind,actor_id,desired_state,reason_code from effect.manual_decision where request_id=?")) {
            q.setString(1,request.value());try(var r=q.executeQuery()) {
                if(!r.next())return false;
                if(!key.threadId().value().equals(r.getString(1))||!key.nodeId().equals(r.getString(2))||!key.idempotencyKey().equals(r.getString(3))
                        ||!actor.kind().name().equals(r.getString(4))||!actor.id().equals(r.getString(5))||!desired.name().equals(r.getString(6))||!reason.equals(r.getString(7)))
                    throw new IllegalStateException("manual decision request id was already used with different content");
                return true;
            }
        }
    }
    private record DecisionRow(long id,AuditSource source,AuditEvent draft){}
}
