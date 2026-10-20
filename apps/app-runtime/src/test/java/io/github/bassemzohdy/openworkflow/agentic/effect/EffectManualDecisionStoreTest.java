package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;

/** Owned actual PostgreSQL/manual source DATA tests, NOT actual operator identity/evidence/PDP/atomic central proof. */
@QuarkusTest @TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class EffectManualDecisionStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    EffectAuditTestDatabase db;
    JdbcInstanceStore registry;
    JdbcEffectLedger ledger;
    JdbcEffectManualDecisions decisions;
    final EffectAuditProducer producer=new EffectAuditProducer();
    static final Subject OWNER=new Subject(SubjectKind.USER,"alíce-𝄞"),OPERATOR=new Subject(SubjectKind.USER,"operator-é");
    record Fixture(EffectScope scope,EffectCommand command) { }
    static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    @BeforeAll void openOnlyOwnDatabase() throws Exception {
        db=EffectAuditTestDatabase.open();registry=new JdbcInstanceStore(db.dataSource);
        try(var c=db.dataSource.getConnection()) {
            c.setAutoCommit(false);try{EffectAuditTestDatabase.sql(c,EffectAuditTestDatabase.resource("V80__effect_manual_decisions.sql"));c.commit();}finally{c.rollback();}
        }
        ledger=new JdbcEffectLedger(db.dataSource,manager,producer);decisions=new JdbcEffectManualDecisions(db.dataSource,manager);
    }
    @AfterAll void closeOnlyOwnDatabase() throws Exception {if(db!=null)db.close();}
    Fixture empty() {
        var request=new StartRequest("manual-"+UUID.randomUUID(),OWNER,new DefinitionRef("agentic/manual-data-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",OWNER);
        var scope=new EffectScope(row.tenant(),row.id(),OWNER);return new Fixture(scope,scope.command("charge","item/é/1","approved-charge","amount=10"));
    }
    Fixture fixture(){var f=empty();ledger.prepareScoped(f.scope(),f.command(),Duration.ZERO,now());ledger.markAttempted(f.command().key());ledger.requireManualResolution(f.command().key(),"effect.outcome.unknown");return f;}
    List<EffectAuditProducer.Retained> history(Fixture f){return Transactions.inTransaction(db.dataSource,c->producer.forKey(c,f.command().key(),0,128));}
    EffectManualDecision decision(Fixture f){return decision(f,EffectState.CONFIRMED);}
    EffectManualDecision decision(Fixture f,EffectState state) {
        return new EffectManualDecision(UlidGenerator.shared().next(),f.scope(),f.command(),history(f).getLast().source().sequence(),state,OPERATOR,new PolicyRef("operation.resolve-effect","fixture-v1"),"a".repeat(64),"b".repeat(64));
    }
    EffectManualDecision copy(EffectManualDecision d,Ulid id,EffectState state,Subject op,PolicyRef policy,String auth,String evidence,long expected) {
        return new EffectManualDecision(id,d.scope(),d.command(),expected,state,op,policy,auth,evidence);
    }
    long count(Fixture f,String table) throws SQLException {
        try(var c=db.dataSource.getConnection();var q=c.prepareStatement("select count(*) from effect."+table+" where thread_name=? and node_id=? and idempotency_key=?")) {
            key(q,f.command().key(),1);try(var r=q.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    boolean queued(Fixture f) throws SQLException {
        try(var c=db.dataSource.getConnection();var q=c.prepareStatement("select closed_at is null from effect.manual_resolution where thread_name=? and node_id=? and idempotency_key=?")) {
            key(q,f.command().key(),1);try(var r=q.executeQuery()){return r.next() && r.getBoolean(1);}
        }
    }
    void unchanged(Fixture f,List<EffectAuditProducer.Retained> history) throws SQLException {
        assertEquals(history,history(f));assertEquals(0,count(f,"manual_decision"));assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());assertTrue(queued(f));
    }
    void committed(Fixture f,EffectManualDecision d,JdbcEffectManualDecisions.Stored stored) throws Exception {
        assertEquals(d,stored.decision());assertEquals(1,count(f,"manual_decision"));assertFalse(queued(f));
        var effect=ledger.find(f.command().key()).orElseThrow();assertEquals(d.outcome(),effect.state());assertEquals(d.reason(),effect.detail());assertEquals(stored.occurredAt(),effect.recordedAt());
        var retained=history(f).getLast();assertEquals(stored.journalId(),retained.source().sequence());assertEquals(AuditSource.Transition.MANUAL,retained.source().transition());
        assertEquals(stored.occurredAt(),retained.draft().occurredAt());assertEquals(new Subject(SubjectKind.SERVICE,"system:effect-ledger"),retained.draft().actor());
        for(var e:d.auditAttributes().entrySet())assertEquals(e.getValue(),retained.draft().attributes().get(e.getKey()));
        try(var c=db.dataSource.getConnection();var q=c.prepareStatement("select decision_fingerprint=effect.manual_fingerprint(m),ack_source_key from effect.manual_decision m join effect.audit_delivery a using(source_event_id) where decision_id=?")) {
            q.setString(1,d.id().value());try(var r=q.executeQuery()){assertTrue(r.next());assertTrue(r.getBoolean(1));assertNull(r.getString(2));}
        }
    }
    @Test void confirmationAtomicallyRetainsFullDecisionOutcomeQueueClosureSourceAndPendingDelivery() throws Exception {
        var f=fixture();var d=decision(f);var stored=decisions.record(d);committed(f,d,stored);assertEquals(4,history(f).size());
    }
    @Test void authoritativeRefusalIsExplicitFailedDecisionAndNeverAnInferredTimeout() throws Exception {
        var f=fixture();var d=decision(f,EffectState.FAILED);var stored=decisions.record(d);committed(f,d,stored);assertEquals("operator.evidence.refused",d.reason());
    }
    @Test void exactRetryFromNewComponentRetainsOriginalDecisionDraftAndTimestamp() throws Exception {
        var f=fixture();var d=decision(f);var stored=decisions.record(d);var history=history(f);
        assertEquals(stored,new JdbcEffectManualDecisions(db.dataSource,manager).record(d));assertEquals(stored,decisions.record(d));assertEquals(history,history(f));committed(f,d,stored);
    }
    @Test void stableIdReadbackRetainsServerAttributionAndRefusesAnotherFullScopeOrCommand() throws Exception {
        var f=fixture();var d=decision(f);assertTrue(decisions.find(f.scope(),f.command(),d.id()).isEmpty());
        var stored=decisions.record(d);var history=history(f);var fresh=new JdbcEffectManualDecisions(db.dataSource,manager);
        assertEquals(stored,fresh.find(f.scope(),f.command(),d.id()).orElseThrow());
        var wrong=new EffectScope(f.scope().tenant(),f.scope().instanceId(),new Subject(SubjectKind.SERVICE,OWNER.id()));
        assertThrows(IllegalStateException.class,()->fresh.find(wrong,f.command(),d.id()));
        for(var command:List.of(new EffectCommand(d.command().key(),"other",d.command().requestFingerprint()),new EffectCommand(d.command().key(),d.command().target(),"c".repeat(64))))
            assertThrows(IllegalStateException.class,()->fresh.find(f.scope(),command,d.id()));
        assertEquals(stored,decisions.record(fresh.find(f.scope(),f.command(),d.id()).orElseThrow().decision()));assertEquals(history,history(f));
    }
    @Test void changedOperatorPolicyEvidenceAuthorizationOutcomeOrExpectedHistoryConflicts() throws Exception {
        var f=fixture();var d=decision(f);decisions.record(d);var saved=history(f);
        for(var changed:List.of(
            copy(d,d.id(),EffectState.FAILED,d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint(),d.expectedJournalId()),
            copy(d,d.id(),d.outcome(),new Subject(SubjectKind.SERVICE,d.operator().id()),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint(),d.expectedJournalId()),
            copy(d,d.id(),d.outcome(),new Subject(SubjectKind.USER,"other"),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint(),d.expectedJournalId()),
            copy(d,d.id(),d.outcome(),d.operator(),new PolicyRef("other","fixture-v1"),d.authorizationFingerprint(),d.evidenceFingerprint(),d.expectedJournalId()),
            copy(d,d.id(),d.outcome(),d.operator(),new PolicyRef(d.policy().policyId(),"v2"),d.authorizationFingerprint(),d.evidenceFingerprint(),d.expectedJournalId()),
            copy(d,d.id(),d.outcome(),d.operator(),d.policy(),"c".repeat(64),d.evidenceFingerprint(),d.expectedJournalId()),
            copy(d,d.id(),d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),"c".repeat(64),d.expectedJournalId()),
            copy(d,d.id(),d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint(),d.expectedJournalId()+1)))
            assertThrows(IllegalStateException.class,()->decisions.record(changed));
        assertEquals(saved,history(f));assertEquals(1,count(f,"manual_decision"));
    }
    @Test void secondDecisionIdCannotResolveTheSameKeyAgain() throws Exception {
        var f=fixture();var d=decision(f);decisions.record(d);var saved=history(f);
        assertThrows(IllegalStateException.class,()->decisions.record(copy(d,UlidGenerator.shared().next(),d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint(),d.expectedJournalId())));
        assertEquals(saved,history(f));assertEquals(1,count(f,"manual_decision"));
    }
    @Test void decisionIdCannotBeReusedForAnotherEffect() throws Exception {
        var f=fixture();var d=decision(f);decisions.record(d);var other=fixture();var next=decision(other);var saved=history(other);
        var reused=copy(next,d.id(),next.outcome(),next.operator(),next.policy(),next.authorizationFingerprint(),next.evidenceFingerprint(),next.expectedJournalId());
        assertThrows(IllegalStateException.class,()->decisions.record(reused));unchanged(other,saved);
    }
    @Test void staleLatestJournalRefusesWithoutErasingTheNewUnknownObservation() throws Exception {
        var f=fixture();var d=decision(f);ledger.requireManualResolution(f.command().key(),"effect.outcome.unknown");var saved=history(f);
        assertThrows(IllegalStateException.class,()->decisions.record(d));unchanged(f,saved);
    }
    @Test void wrongFullOwnerTargetOrRequestRefusesWithoutChangingHistory() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        var wrong=new EffectScope(f.scope().tenant(),f.scope().instanceId(),new Subject(SubjectKind.SERVICE,OWNER.id()));
        assertThrows(IllegalStateException.class,()->decisions.record(new EffectManualDecision(d.id(),wrong,d.command(),d.expectedJournalId(),d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint())));
        for(var command:List.of(new EffectCommand(d.command().key(),"other",d.command().requestFingerprint()),new EffectCommand(d.command().key(),d.command().target(),"c".repeat(64))))
            assertThrows(IllegalStateException.class,()->decisions.record(new EffectManualDecision(d.id(),d.scope(),command,d.expectedJournalId(),d.outcome(),d.operator(),d.policy(),d.authorizationFingerprint(),d.evidenceFingerprint())));
        unchanged(f,saved);
    }
    @Test void unattemptedIntentAndMissingQueueRefuse() throws Exception {
        for(boolean attempted:List.of(false,true)) {
            var f=empty();ledger.prepareScoped(f.scope(),f.command(),Duration.ZERO,now());
            if(attempted)ledger.markAttempted(f.command().key());else ledger.requireManualResolution(f.command().key(),"effect.outcome.unknown");
            var saved=history(f);assertThrows(IllegalStateException.class,()->decisions.record(decision(f)));assertEquals(saved,history(f));assertEquals(0,count(f,"manual_decision"));assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());
        }
    }
    @Test void resolvedEffectCannotAcquireItsFirstManualDecision() throws Exception {
        var f=fixture();var d=decision(f);ledger.recordOutcome(f.command().key(),EffectState.CONFIRMED,"target.confirmed");
        ledger.requireManualResolution(f.command().key(),"effect.compensation.unresolved");var saved=history(f);
        assertThrows(IllegalStateException.class,()->decisions.record(d));assertEquals(saved,history(f));assertEquals(0,count(f,"manual_decision"));assertEquals(EffectState.CONFIRMED,ledger.find(f.command().key()).orElseThrow().state());
    }
    @Test void legacyUnprovenIntentCannotBeUpgradedIntoManualProvenance() throws Exception {
        var f=empty();var raw=new JdbcEffectLedger(db.dataSource);raw.recordIntent(new Effect(f.command().key().threadId(),f.command().key().nodeId(),f.command().key().idempotencyKey(),f.command().target(),EffectState.INTENDED,null,now()));
        raw.markAttempted(f.command().key());raw.requireManualResolution(f.command().key(),"effect.outcome.unknown");
        var d=new EffectManualDecision(UlidGenerator.shared().next(),f.scope(),f.command(),1,EffectState.CONFIRMED,OPERATOR,new PolicyRef("fixture","v1"),"a".repeat(64),"b".repeat(64));
        assertThrows(IllegalStateException.class,()->decisions.record(d));assertEquals(0,count(f,"manual_decision"));assertTrue(history(f).isEmpty());
    }
    @Test void existingCrossProcessCompatibleClaimPreventsConcurrentManualMutation() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        ledger.exclusively(f.command().key(),()->{assertThrows(JdbcEffectLedger.EffectBusyException.class,()->new JdbcEffectManualDecisions(db.dataSource,manager).record(d));return null;});
        unchanged(f,saved);committed(f,d,decisions.record(d));
    }
    @Test void simultaneousDistinctDecisionsProduceExactlyOneImmutableWinner() throws Exception {
        var f=fixture();var first=decision(f);var second=decision(f,EffectState.FAILED);var start=new CyclicBarrier(2);var executor=Executors.newFixedThreadPool(2);
        try {
            var tasks=new ArrayList<Future<JdbcEffectManualDecisions.Stored>>();
            for(var d:List.of(first,second))tasks.add(executor.submit(()->{start.await(5,TimeUnit.SECONDS);try{return new JdbcEffectManualDecisions(db.dataSource,manager).record(d);}catch(JdbcEffectLedger.EffectBusyException|IllegalStateException refused){return null;}}));
            var results=new ArrayList<JdbcEffectManualDecisions.Stored>();for(var task:tasks){var value=task.get(15,TimeUnit.SECONDS);if(value!=null)results.add(value);}
            assertEquals(1,results.size());assertEquals(1,count(f,"manual_decision"));assertEquals(4,history(f).size());
            var winner=results.getFirst();assertEquals(winner,decisions.record(winner.decision()));var loser=winner.decision().equals(first)?second:first;
            assertThrows(IllegalStateException.class,()->decisions.record(loser));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void lostActualCommitReplyRetainsEverythingAndFreshComponentReadsSameDecision() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        assertThrows(StoreUnavailableException.class,()->new JdbcEffectManualDecisions(fault(Fault.AFTER_COMMIT),manager).record(d));
        var committed=history(f);assertEquals(saved,committed.subList(0,saved.size()));assertEquals(4,committed.size());
        var stored=new JdbcEffectManualDecisions(db.dataSource,manager).record(d);committed(f,d,stored);assertEquals(committed,history(f));
    }
    @Test void failureBeforeActualCommitRollsBackDecisionOutcomeClosureAndAuditSource() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        assertThrows(StoreUnavailableException.class,()->new JdbcEffectManualDecisions(fault(Fault.BEFORE_COMMIT),manager).record(d));unchanged(f,saved);committed(f,d,decisions.record(d));
    }
    @Test void sourceInsertionFailureRollsBackTheWholeDecisionTransaction() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        try(var c=db.dataSource.getConnection()) {EffectAuditTestDatabase.sql(c,"create function effect.gpt_manual_refuse() returns trigger language plpgsql as $$ begin if new.transition='MANUAL' then raise exception 'owned fixture refusal' using errcode='23514'; end if; return new; end $$; create trigger gpt_manual_refuse before insert on effect.audit_source for each row execute function effect.gpt_manual_refuse()");}
        try {assertSqlState("23514",assertThrows(StoreUnavailableException.class,()->decisions.record(d)));unchanged(f,saved);}
        finally{try(var c=db.dataSource.getConnection()){EffectAuditTestDatabase.sql(c,"drop trigger gpt_manual_refuse on effect.audit_source; drop function effect.gpt_manual_refuse()");}}
        committed(f,d,decisions.record(d));
    }
    @Test void databaseRefusesAChangedDecisionFingerprintAndRollsBackAllFacts() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        assertSqlState("23514",assertThrows(StoreUnavailableException.class,()->new JdbcEffectManualDecisions(fault(Fault.BAD_FINGERPRINT),manager).record(d)));unchanged(f,saved);
    }
    @Test void deferredGuardRefusesAnOmittedOutcomeEvenWhenTheApplicationReportsUpdateSuccess() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        assertSqlState("23514",assertThrows(StoreUnavailableException.class,()->new JdbcEffectManualDecisions(fault(Fault.SKIP_OUTCOME),manager).record(d)));unchanged(f,saved);
    }
    @Test void deferredForeignKeyRefusesAnOmittedSourceAndRollsBackOutcomeAndClosure() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);
        var failure=assertThrows(StoreUnavailableException.class,()->new JdbcEffectManualDecisions(fault(Fault.SKIP_SOURCE),manager).record(d));
        assertTrue(sqlStates(failure).stream().anyMatch(x->Set.of("23503","23514").contains(x)));unchanged(f,saved);
    }
    @Test void manualDecisionRowsCannotBeChangedDeletedOrTruncated() throws Exception {
        var f=fixture();var d=decision(f);var stored=decisions.record(d);
        for(String text:List.of("update effect.manual_decision set policy_version='forged' where decision_id=?","delete from effect.manual_decision where decision_id=?","truncate effect.manual_decision")) {
            try(var c=db.dataSource.getConnection();var q=c.prepareStatement(text)) {
                if(text.contains("?"))q.setString(1,d.id().value());var refused=assertThrows(SQLException.class,q::executeUpdate);
                // V77's reused retention guard deliberately reports insufficient_privilege, not check_violation.
                assertEquals("42501",refused.getSQLState());assertTrue(refused.getMessage().contains("effect audit provenance and source are immutable and retained"));
            }
        }
        assertEquals(stored,decisions.record(d));
    }
    @Test void ambientCallerRollbackCannotEraseTheOwnedCommittedManualDecision() throws Exception {
        var f=fixture();var d=decision(f);JdbcEffectManualDecisions.Stored stored;
        caller.begin();try {
            stored=decisions.record(d);assertEquals(Status.STATUS_ACTIVE,manager.getStatus());
            assertEquals(stored,decisions.find(f.scope(),f.command(),d.id()).orElseThrow());assertEquals(Status.STATUS_ACTIVE,manager.getStatus());
        }finally{caller.rollback();}
        assertEquals(stored,decisions.record(d));committed(f,d,stored);
    }
    @Test void existingInterruptionRefusesMutationAndPreservesTheInterruptFlag() throws Exception {
        var f=fixture();var d=decision(f);var saved=history(f);Thread.currentThread().interrupt();
        try{assertThrows(CancellationException.class,()->decisions.record(d));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
        unchanged(f,saved);
    }
    @Test void pendingManualReceiptBlocksCachedResultUntilActualDataReceiptReadback() throws Exception {
        var f=fixture();var d=decision(f);decisions.record(d);var metrics=new SimpleMeterRegistry();var target=new NoTarget(f.command().target());
        try {
            var refused=new AuditedEffects(db.dataSource,manager,(s,e)->{if(s.transition()==AuditSource.Transition.MANUAL)throw new IllegalStateException("manual audit dependency unavailable");return EffectHttpTestSupport.receipt(db.dataSource,s,e);},new EffectTelemetry(OpenTelemetry.noop()),metrics);
            assertThrows(IllegalStateException.class,()->refused.invoke(new EffectInvocation(f.scope(),f.command(),null),target,(i,b)->{}));
            var gate=new AuditedEffects(db.dataSource,manager,(s,e)->EffectHttpTestSupport.receipt(db.dataSource,s,e),new EffectTelemetry(OpenTelemetry.noop()),metrics);
            assertEquals(EffectState.CONFIRMED,gate.invoke(new EffectInvocation(f.scope(),f.command(),null),target,(i,b)->{}).state());assertEquals(0,target.calls);
        }finally{metrics.close();}
    }
    @Test void terminalRegistryRetainsManualDataButDoesNotGrantOrdinaryExecution() throws Exception {
        var f=fixture();var d=decision(f);var row=registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.FAILED,row.engineInstanceId(),1,"ended",null),row.version(),"test.failed",OWNER);
        committed(f,d,decisions.record(d));var metrics=new SimpleMeterRegistry();var target=new NoTarget(f.command().target());
        try {var gate=new AuditedEffects(db.dataSource,manager,(s,e)->EffectHttpTestSupport.receipt(db.dataSource,s,e),new EffectTelemetry(OpenTelemetry.noop()),metrics);
            assertThrows(IllegalStateException.class,()->gate.invoke(new EffectInvocation(f.scope(),f.command(),null),target,(i,b)->{}));assertEquals(0,target.calls);
        }finally{metrics.close();}
    }
    @Test void confirmedManualUndoDoesNotMarkOriginalCompensatedUntilSeparateCompensationProtocol() throws Exception {
        var f=fixture();ledger.recordOutcome(f.command().key(),EffectState.CONFIRMED,"target.confirmed");
        var undo=f.scope().command("refund","undo","approved-refund","amount=-10");ledger.prepareCompensation(f.command().key(),undo,Duration.ZERO,now());ledger.markAttempted(undo.key());ledger.requireManualResolution(undo.key(),"effect.outcome.unknown");
        var uf=new Fixture(f.scope(),undo);var d=decision(uf);committed(uf,d,decisions.record(d));assertEquals(EffectState.CONFIRMED,ledger.find(f.command().key()).orElseThrow().state());
        var saved=history(uf);var metrics=new SimpleMeterRegistry();var target=new NoTarget(undo.target());
        try {var gate=new AuditedEffects(db.dataSource,manager,(s,e)->EffectHttpTestSupport.receipt(db.dataSource,s,e),new EffectTelemetry(OpenTelemetry.noop()),metrics);
            assertEquals(EffectState.COMPENSATED,gate.invoke(new EffectInvocation(f.scope(),undo,f.command().key()),target,(i,b)->{}).state());assertEquals(0,target.calls);
        }finally{metrics.close();}
        assertEquals(saved,history(uf));assertEquals(d,decisions.record(d).decision());
    }
    static final class NoTarget implements EffectTarget {
        final String target;int calls;NoTarget(String target){this.target=target;}
        public String target(){return target;}
        public Reply execute(String key){calls++;throw new AssertionError("manual storage/cache must not dispatch");}
        public Reply lookup(String key){calls++;throw new AssertionError("manual storage/cache must not look up");}
    }
    enum Fault { BEFORE_COMMIT,AFTER_COMMIT,BAD_FINGERPRINT,SKIP_OUTCOME,SKIP_SOURCE }
    DataSource fault(Fault fault) {
        var once=new AtomicBoolean(true);
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object value=call(m,db.dataSource,a);if(!m.getName().equals("getConnection"))return value;var connection=(Connection)value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                if(cm.getName().equals("commit") && (fault==Fault.BEFORE_COMMIT || fault==Fault.AFTER_COMMIT) && once.compareAndSet(true,false)) {
                    if(fault==Fault.BEFORE_COMMIT)throw new SQLException("owned manual commit unavailable","08006");
                    call(cm,connection,ca);throw new SQLException("owned manual commit reply lost","08006");
                }
                Object result=call(cm,connection,ca);
                if(!cm.getName().equals("prepareStatement") || ca==null || !(ca[0] instanceof String text))return result;
                boolean bad=fault==Fault.BAD_FINGERPRINT && text.contains("insert into effect.manual_decision(");
                boolean skip=(fault==Fault.SKIP_OUTCOME && text.startsWith("update effect.ledger set state=")) || (fault==Fault.SKIP_SOURCE && text.contains("insert into effect.audit_source("));
                if(!bad && !skip)return result;var statement=(PreparedStatement)result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->{
                    if(skip && sm.getName().equals("executeUpdate"))return 1;
                    if(bad && sm.getName().equals("setString") && ((Integer)sa[0])==19){var altered=sa.clone();altered[1]="0".repeat(64);return call(sm,statement,altered);}
                    return call(sm,statement,sa);
                });
            });
        });
    }
    static Object call(Method method,Object target,Object[] args) throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException failed){throw failed.getCause();}}
    static void key(PreparedStatement q,EffectKey key,int offset) throws SQLException {q.setString(offset,key.threadId().value());q.setString(offset+1,key.nodeId());q.setString(offset+2,key.idempotencyKey());}
    static Set<String> sqlStates(Throwable failure) {var states=new HashSet<String>();for(var t=failure;t!=null;t=t.getCause())if(t instanceof SQLException sql)states.add(sql.getSQLState());return states;}
    static void assertSqlState(String expected,Throwable failure){assertTrue(sqlStates(failure).contains(expected),()->"expected SQLSTATE "+expected+", got "+sqlStates(failure));}
}
