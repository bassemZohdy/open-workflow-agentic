package io.github.bassemzohdy.openworkflow.agentic.runtime.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;

/**
 * Explicit trusted persistence DATA, no CDI/operator endpoint/authorization/provider callback. The real current
 * operator/oa.recover/resolve-effect/evidence producer and mandatory audit/current-result boundary MUST precede
 * production exposure. A successful return is historical storage acknowledgment, never permission/Flow progress.
 * ONE immutable decision per actual attempted, queued INTENDED key; cross-process claims match durable execution.
 * Atomic decision/outcome/queue closure/journal/source/pending delivery. Unknown commit reply reuses the SAME tuple.
 */
public final class JdbcEffectManualDecisions {
    private static final String KEY="thread_name=? and node_id=? and idempotency_key=?";
    private final DataSource data;
    private final TransactionManager manager;
    private final EffectAuditProducer producer=new EffectAuditProducer();
    private final JdbcEffectLedger ledger;
    public record Stored(EffectManualDecision decision,long journalId,Instant occurredAt) {
        public Stored {
            Objects.requireNonNull(decision);Objects.requireNonNull(occurredAt);
            if(journalId<=decision.expectedJournalId() || occurredAt.getNano()%1000!=0)throw inconsistent();
        }
    }
    public JdbcEffectManualDecisions(DataSource data,TransactionManager manager) {
        this.data=Objects.requireNonNull(data);this.manager=Objects.requireNonNull(manager);
        this.ledger=new JdbcEffectLedger(data,manager,producer);
    }
    public Stored record(EffectManualDecision decision) {
        Objects.requireNonNull(decision);interrupted();Transaction prior;
        try{prior=manager.suspend();}catch(Exception failed){throw new StoreUnavailableException("manual caller transaction cannot be suspended",failed);}
        try {
            var stored=ledger.exclusively(decision.command().key(),()->EffectStoreTransactions.readCommitted(data,c->record(c,decision)));
            interrupted();return stored;
        }finally{if(prior!=null)try{manager.resume(prior);}catch(Exception failed){throw new StoreUnavailableException("manual caller transaction cannot be resumed",failed);}}
    }
    private Stored record(Connection c,EffectManualDecision d) throws SQLException {
        // Match the producer lock order. Terminal status is history, not an automatic recovery authorization.
        try(var q=c.prepareStatement("select tenant,owner_kind,owner_id from task.instance where id=? for update")) {
            q.setString(1,d.scope().instanceId().value());try(var r=q.executeQuery()) {
                if(!r.next() || !d.scope().tenant().equals(r.getString(1)) || !d.scope().owner().kind().name().equals(r.getString(2)) || !d.scope().owner().id().equals(r.getString(3)))throw inconsistent();
            }
        }
        String state;boolean attempted;
        try(var q=c.prepareStatement("select state,attempted,target,request_fingerprint from effect.ledger where "+KEY+" for update")) {
            bind(q,d.command().key(),1);try(var r=q.executeQuery()) {
                if(!r.next() || !d.command().target().equals(r.getString(3)) || !d.command().requestFingerprint().equals(r.getString(4)))throw inconsistent();
                state=r.getString(1);attempted=r.getBoolean(2);
            }
        }
        // Historical exact retry is allowed after subsequent compensation; it never re-resolves or rewrites time.
        try(var q=c.prepareStatement("select * from effect.manual_decision where decision_id=? or ("+KEY+")")) {
            q.setString(1,d.id().value());bind(q,d.command().key(),2);try(var r=q.executeQuery()) {
                if(r.next()) {
                    var stored=read(r);if(!stored.decision().equals(d) || r.next())throw inconsistent();
                    producer.find(c,stored.journalId()).orElseThrow(JdbcEffectManualDecisions::inconsistent);return stored;
                }
            }
        }
        var bound=producer.bound(c,d.command().key()).orElseThrow(JdbcEffectManualDecisions::inconsistent);
        if(!bound.scope().equals(d.scope()) || !state.equals("INTENDED") || !attempted)throw inconsistent();
        producer.find(c,bound.initialId()).orElseThrow(JdbcEffectManualDecisions::inconsistent);
        long latest;
        try(var q=c.prepareStatement("select max(id) from effect.event where "+KEY)) {
            bind(q,d.command().key(),1);try(var r=q.executeQuery()){r.next();latest=r.getLong(1);}
        }
        if(latest!=d.expectedJournalId())throw inconsistent();
        producer.find(c,latest).orElseThrow(JdbcEffectManualDecisions::inconsistent);
        try(var q=c.prepareStatement("select closed_at from effect.manual_resolution where "+KEY+" for update")) {
            bind(q,d.command().key(),1);try(var r=q.executeQuery()){if(!r.next() || r.getTimestamp(1)!=null)throw inconsistent();}
        }
        interrupted(); // After all blocking metadata/row-lock work, before retaining a new decision.
        long id;Instant occurred;
        try(var q=c.prepareStatement("insert into effect.event(thread_name,node_id,idempotency_key,from_state,to_state,reason) values(?,?,?,'INTENDED',?,'effect.manual') returning id,occurred_at")) {
            bind(q,d.command().key(),1);q.setString(4,d.outcome().name());try(var r=q.executeQuery()){r.next();id=r.getLong(1);occurred=r.getTimestamp(2).toInstant();}
        }
        try(var q=c.prepareStatement("""
            insert into effect.manual_decision(decision_id,schema_version,tenant,registry_instance_id,owner_kind,owner_id,
              thread_name,node_id,idempotency_key,target,request_fingerprint,expected_event_id,outcome,operator_kind,operator_id,
              policy_id,policy_version,authorization_fingerprint,evidence_fingerprint,decision_fingerprint,source_event_id,occurred_at)
            values(?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            q.setString(1,d.id().value());q.setString(2,d.scope().tenant());q.setString(3,d.scope().instanceId().value());
            q.setString(4,d.scope().owner().kind().name());q.setString(5,d.scope().owner().id());bind(q,d.command().key(),6);
            q.setString(9,d.command().target());q.setString(10,d.command().requestFingerprint());q.setLong(11,d.expectedJournalId());
            q.setString(12,d.outcome().name());q.setString(13,d.operator().kind().name());q.setString(14,d.operator().id());
            q.setString(15,d.policy().policyId());q.setString(16,d.policy().policySetVersion());q.setString(17,d.authorizationFingerprint());
            q.setString(18,d.evidenceFingerprint());q.setString(19,d.fingerprint());q.setLong(20,id);q.setTimestamp(21,Timestamp.from(occurred));q.executeUpdate();
        }
        producer.capture(c,new EffectAuditProducer.Fact(id,occurred,d.command().key(),EffectState.INTENDED,d.outcome(),"effect.manual"));
        try(var q=c.prepareStatement("update effect.ledger set state=?,detail=?,recorded_at=? where "+KEY)) {
            q.setString(1,d.outcome().name());q.setString(2,d.reason());q.setTimestamp(3,Timestamp.from(occurred));bind(q,d.command().key(),4);
            if(q.executeUpdate()!=1)throw inconsistent();
        }
        try(var q=c.prepareStatement("update effect.manual_resolution set closed_at=? where "+KEY+" and closed_at is null")) {
            q.setTimestamp(1,Timestamp.from(occurred));bind(q,d.command().key(),2);if(q.executeUpdate()!=1)throw inconsistent();
        }
        return new Stored(d,id,occurred);
    }
    static Stored forJournal(Connection c,long journalId) throws SQLException {
        try(var q=c.prepareStatement("select * from effect.manual_decision where source_event_id=?")) {
            q.setLong(1,journalId);try(var r=q.executeQuery()){if(!r.next())throw inconsistent();return read(r);}
        }
    }
    private static Stored read(ResultSet r) throws SQLException {
        var scope=new EffectScope(r.getString("tenant"),Ulid.parse(r.getString("registry_instance_id")),new Subject(SubjectKind.valueOf(r.getString("owner_kind")),r.getString("owner_id")));
        var key=new EffectKey(io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId.parse(r.getString("thread_name")),r.getString("node_id"),r.getString("idempotency_key"));
        var decision=new EffectManualDecision(Ulid.parse(r.getString("decision_id")),scope,new EffectCommand(key,r.getString("target"),r.getString("request_fingerprint")),r.getLong("expected_event_id"),EffectState.valueOf(r.getString("outcome")),
                new Subject(SubjectKind.valueOf(r.getString("operator_kind")),r.getString("operator_id")),new PolicyRef(r.getString("policy_id"),r.getString("policy_version")),r.getString("authorization_fingerprint"),r.getString("evidence_fingerprint"));
        if(r.getInt("schema_version")!=1 || !decision.fingerprint().equals(r.getString("decision_fingerprint")))throw inconsistent();
        return new Stored(decision,r.getLong("source_event_id"),r.getTimestamp("occurred_at").toInstant());
    }
    private static void bind(PreparedStatement q,EffectKey key,int offset) throws SQLException {q.setString(offset,key.threadId().value());q.setString(offset+1,key.nodeId());q.setString(offset+2,key.idempotencyKey());}
    private static IllegalStateException inconsistent(){return new IllegalStateException("manual decision identity, scope, evidence or unresolved history differs");}
    private static void interrupted(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("manual decision interrupted; retain original identity");}
}
