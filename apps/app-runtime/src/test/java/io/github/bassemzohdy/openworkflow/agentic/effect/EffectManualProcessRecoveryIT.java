package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.effect.EffectManualProcessWorker.Mode.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.EffectTarget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;

/** Actual owned JVM death/PG rollback and stable-id reopen; trusted DATA, not actual operator/PDP/Flow permission. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EffectManualProcessRecoveryIT {
    EffectAuditTestDatabase db;
    JdbcInstanceStore registry;
    JdbcEffectLedger ledger;
    JdbcEffectManualDecisions decisions;
    final EffectAuditProducer producer=new EffectAuditProducer();
    final jakarta.transaction.TransactionManager manager=com.arjuna.ats.jta.TransactionManager.transactionManager();
    static final Subject OWNER=new Subject(SubjectKind.USER,"process-alíce-𝄞");
    record Fixture(EffectScope scope,EffectCommand command,EffectKey original) { }
    record Queue(Instant first,Instant last,Instant closed) { }
    static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    @BeforeAll void openOnlyOwnedDatabase() throws Exception {
        db=EffectAuditTestDatabase.open();registry=new JdbcInstanceStore(db.dataSource);
        try(var c=db.dataSource.getConnection()) {
            c.setAutoCommit(false);try{EffectAuditTestDatabase.sql(c,EffectAuditTestDatabase.resource("V80__effect_manual_decisions.sql"));c.commit();}finally{c.rollback();}
        }
        ledger=new JdbcEffectLedger(db.dataSource,manager,producer);decisions=new JdbcEffectManualDecisions(db.dataSource,manager);
    }
    @AfterAll void dropOnlyOwnedDatabase() throws Exception {if(db!=null)db.close();}
    Fixture fixture(boolean undo) {
        var request=new StartRequest("manual-process-"+UUID.randomUUID(),OWNER,new DefinitionRef("agentic/manual-process-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",OWNER);
        var scope=new EffectScope(row.tenant(),row.id(),OWNER);var command=EffectManualProcessWorker.command(scope,false);
        ledger.prepareScoped(scope,command,Duration.ZERO,now());ledger.markAttempted(command.key());
        if(undo) {
            ledger.recordOutcome(command.key(),EffectState.CONFIRMED,"fixture.target.confirmed");var refund=EffectManualProcessWorker.command(scope,true);
            ledger.prepareCompensation(command.key(),refund,Duration.ZERO,now());ledger.markAttempted(refund.key());ledger.requireManualResolution(refund.key(),"effect.outcome.unknown");
            return new Fixture(scope,refund,command.key());
        }
        ledger.requireManualResolution(command.key(),"effect.outcome.unknown");return new Fixture(scope,command,null);
    }
    EffectManualDecision decision(Fixture f,EffectState outcome){return EffectManualProcessWorker.decision(f.scope(),f.command(),UlidGenerator.shared().next(),history(f).getLast().source().sequence(),outcome);}
    List<EffectAuditProducer.Retained> history(Fixture f){return Transactions.inTransaction(db.dataSource,c->producer.forKey(c,f.command().key(),0,128));}
    Queue queue(Fixture f) throws SQLException {
        try(var c=db.dataSource.getConnection();var q=c.prepareStatement("select first_seen,last_seen,closed_at from effect.manual_resolution where thread_name=? and node_id=? and idempotency_key=?")) {
            key(q,f.command().key());try(var r=q.executeQuery()){assertTrue(r.next());var closed=r.getTimestamp(3);return new Queue(r.getTimestamp(1).toInstant(),r.getTimestamp(2).toInstant(),closed==null?null:closed.toInstant());}
        }
    }
    void counts(Fixture f,int facts,int manual) throws SQLException {
        try(var c=db.dataSource.getConnection();var q=c.prepareStatement("""
            select (select count(*) from effect.event where thread_name=? and node_id=? and idempotency_key=?),
              (select count(*) from effect.manual_decision where thread_name=? and node_id=? and idempotency_key=?),
              (select count(*) from effect.audit_source a join effect.event e on e.id=a.source_event_id where e.thread_name=? and e.node_id=? and e.idempotency_key=?),
              (select count(*) from effect.audit_delivery where thread_name=? and node_id=? and idempotency_key=?),
              (select count(*) from effect.audit_delivery where thread_name=? and node_id=? and idempotency_key=? and ack_source_key is not null)
            """)) {
            for(int offset:List.of(1,4,7,10,13))key(q,f.command().key(),offset);
            try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(facts,r.getLong(1));assertEquals(manual,r.getLong(2));assertEquals(facts,r.getLong(3));assertEquals(facts,r.getLong(4));assertEquals(0,r.getLong(5));}
        }
    }
    @Test void confirmationDeathAfterSourceBeforeOutcomeRollsBackAllManualFacts() throws Exception {recover(fixture(false),EffectState.CONFIRMED,AFTER_SOURCE);}
    @Test void refusalDeathAfterSourceBeforeOutcomeRollsBackAllManualFacts() throws Exception {recover(fixture(false),EffectState.FAILED,AFTER_SOURCE);}
    @Test void confirmationDeathBeforeCommitRollsBackOutcomeQueueClosureAndSources() throws Exception {recover(fixture(false),EffectState.CONFIRMED,BEFORE_COMMIT);}
    @Test void refusalDeathBeforeCommitRollsBackOutcomeQueueClosureAndSources() throws Exception {recover(fixture(false),EffectState.FAILED,BEFORE_COMMIT);}
    @Test void confirmationDeathAfterCommitKeepsOriginalAcrossFreshRecordAndReadWorkers() throws Exception {recover(fixture(false),EffectState.CONFIRMED,AFTER_COMMIT);}
    @Test void refusalDeathAfterCommitKeepsOriginalAcrossFreshRecordAndReadWorkers() throws Exception {recover(fixture(false),EffectState.FAILED,AFTER_COMMIT);}
    private JdbcEffectManualDecisions.Stored recover(Fixture f,EffectState outcome,EffectManualProcessWorker.Mode mode) throws Exception {
        var d=decision(f,outcome);var original=history(f);var queued=queue(f);assertEquals(3,original.size());assertNull(queued.closed());counts(f,3,0);
        child(f,d,mode);
        var committed=decisions.find(f.scope(),f.command(),d.id());
        if(mode.committed) {
            assertTrue(committed.isPresent());assertEquals(outcome,ledger.find(f.command().key()).orElseThrow().state());counts(f,4,1);
            assertEquals(original,history(f).subList(0,3));assertEquals(committed.orElseThrow().occurredAt(),queue(f).closed());
        } else {
            assertTrue(committed.isEmpty());assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());counts(f,3,0);assertEquals(original,history(f));assertEquals(queued,queue(f));
        }
        child(f,d,RECORD);var saved=decisions.find(f.scope(),f.command(),d.id()).orElseThrow();var savedHistory=history(f);var savedQueue=queue(f);
        if(mode.committed)assertEquals(committed.orElseThrow(),saved,"exact original id/fingerprint/time/journal must survive lost process reply");
        var effect=ledger.find(f.command().key()).orElseThrow();assertEquals(d,saved.decision());assertEquals(original,savedHistory.subList(0,3));
        assertEquals(outcome,effect.state());assertEquals(d.reason(),effect.detail());assertEquals(saved.occurredAt(),effect.recordedAt());
        assertEquals(queued.first(),savedQueue.first());assertEquals(queued.last(),savedQueue.last());assertEquals(saved.occurredAt(),savedQueue.closed());
        var manual=savedHistory.getLast();assertEquals(AuditSource.Transition.MANUAL,manual.source().transition());assertEquals(saved.journalId(),manual.source().sequence());assertEquals(saved.occurredAt(),manual.draft().occurredAt());
        for(var entry:d.auditAttributes().entrySet())assertEquals(entry.getValue(),manual.draft().attributes().get(entry.getKey()));
        child(f,d,FIND);child(f,d,RECORD);
        assertEquals(saved,decisions.find(f.scope(),f.command(),d.id()).orElseThrow());assertEquals(savedHistory,history(f));assertEquals(savedQueue,queue(f));counts(f,4,1);
        return saved;
    }
    @Test void undoCommitDeathDoesNotCompensateOriginalUntilSeparateConfirmedUndoProtocol() throws Exception {
        var f=fixture(true);recover(f,EffectState.CONFIRMED,AFTER_COMMIT);assertEquals(EffectState.CONFIRMED,ledger.find(f.original()).orElseThrow().state());
        var calls=new java.util.concurrent.atomic.AtomicInteger();var target=noTarget(f.command().target(),calls);var metrics=new SimpleMeterRegistry();
        try {var gate=new AuditedEffects(db.dataSource,manager,(s,d)->EffectHttpTestSupport.receipt(db.dataSource,s,d),new EffectTelemetry(OpenTelemetry.noop()),metrics);
            assertEquals(EffectState.COMPENSATED,gate.invoke(new EffectInvocation(f.scope(),f.command(),f.original()),target,(i,b)->{}).state());assertEquals(0,calls.get());
        }finally{metrics.close();}
    }
    @Test void terminalRegistryCommitDeathRetainsDataWithoutOrdinaryExecutionPermission() throws Exception {
        var f=fixture(false);var row=registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.FAILED,row.engineInstanceId(),1,"test-ended",null),row.version(),"test.failed",OWNER);
        recover(f,EffectState.CONFIRMED,AFTER_COMMIT);var calls=new java.util.concurrent.atomic.AtomicInteger();var metrics=new SimpleMeterRegistry();
        try {var gate=new AuditedEffects(db.dataSource,manager,(s,d)->EffectHttpTestSupport.receipt(db.dataSource,s,d),new EffectTelemetry(OpenTelemetry.noop()),metrics);
            assertThrows(IllegalStateException.class,()->gate.invoke(new EffectInvocation(f.scope(),f.command(),null),noTarget(f.command().target(),calls),(i,b)->{}));assertEquals(0,calls.get());
        }finally{metrics.close();}
    }
    private static EffectTarget noTarget(String id,java.util.concurrent.atomic.AtomicInteger calls) {
        return new EffectTarget(){public String target(){return id;}public Reply execute(String key){calls.incrementAndGet();throw new AssertionError("manual history cannot dispatch");}public Reply lookup(String key){calls.incrementAndGet();throw new AssertionError("manual history cannot look up");}};
    }
    private void child(Fixture f,EffectManualDecision d,EffectManualProcessWorker.Mode mode) throws Exception {
        Path parent=Path.of("target","process-logs").toAbsolutePath().normalize();Files.createDirectories(parent);
        Path directory=Files.createDirectory(parent.resolve("gpt-manual-"+UUID.randomUUID())),log=directory.resolve("manual-worker.log"),arguments=directory.resolve("manual.args");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String absolute=String.join(File.pathSeparator,Arrays.stream(cp.split(Pattern.quote(File.pathSeparator),-1)).map(p->Path.of(p).toAbsolutePath().normalize().toString()).toList());
        var args=new StringBuilder("-cp\n").append(quote(absolute)).append('\n').append(EffectManualProcessWorker.class.getName()).append('\n');
        for(String value:List.of(db.name,f.scope().tenant(),f.scope().instanceId().value(),d.id().value(),Long.toString(d.expectedJournalId()),d.outcome().name(),f.original()==null?"ROOT":"UNDO",mode.name()))args.append(quote(value)).append('\n');
        Files.writeString(arguments,args,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+arguments).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45,TimeUnit.SECONDS),()->"owned manual worker timed out: "+log);assertEquals(mode.exit,process.exitValue(),()->diagnostics(log));
            var lines=Files.readAllLines(log);var marker=mode.exit!=0?"owned manual worker halt "+mode.name():mode==FIND?"owned manual worker read SAME_DECISION":"owned manual worker record SAME_DECISION";
            assertEquals(1,lines.stream().filter(marker::equals).count(),()->diagnostics(log));
            var pids=lines.stream().filter(s->s.startsWith("owned manual backend ")).map(s->Integer.parseInt(s.substring("owned manual backend ".length()))).distinct().toList();
            assertEquals(mode==FIND?1:2,pids.size(),()->diagnostics(log));awaitOwnedBackendsGone(pids,log);
        }finally{if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS),"owned manual worker did not terminate");}}
    }
    /** Wait for the ACTUAL exited child's recorded sessions; never terminate a backend or retry a failed mutation. */
    private void awaitOwnedBackendsGone(List<Integer> pids,Path log) throws Exception {
        assertTrue(pids.stream().allMatch(pid->pid>0));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        try(var c=db.dataSource.getConnection();var q=c.prepareStatement("select count(*) from pg_stat_activity where datname=? and pid in ("+String.join(",",Collections.nCopies(pids.size(),"?"))+")")) {
            c.setAutoCommit(true);q.setQueryTimeout(2);q.setString(1,db.name);for(int i=0;i<pids.size();i++)q.setInt(i+2,pids.get(i));
            while(true) {
                try(var r=q.executeQuery()){assertTrue(r.next());if(r.getInt(1)==0)return;}
                if(System.nanoTime()>=until)fail("exited owned manual worker sessions remain: "+log);Thread.sleep(20);
            }
        }
    }
    private static void key(PreparedStatement q,EffectKey key) throws SQLException {key(q,key,1);}
    private static void key(PreparedStatement q,EffectKey key,int start) throws SQLException {q.setString(start,key.threadId().value());q.setString(start+1,key.nodeId());q.setString(start+2,key.idempotencyKey());}
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String diagnostics(Path log){try(var lines=Files.lines(log)){return log+"\n"+String.join("\n",lines.limit(100).toList());}catch(Exception failed){return log.toString();}}
}
