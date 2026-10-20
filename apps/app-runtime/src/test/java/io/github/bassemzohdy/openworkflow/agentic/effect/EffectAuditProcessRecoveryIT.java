package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import static io.github.bassemzohdy.openworkflow.agentic.effect.EffectAuditProcessWorker.Mode.*;

/** Actual standalone worker death. Central receipt DATA fixtures are not an atomic appendOnce consumer. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EffectAuditProcessRecoveryIT {
    EffectAuditTestDatabase database;
    JdbcInstanceStore registry;
    final EffectAuditProducer producer=new EffectAuditProducer();
    record Run(EffectScope scope,EffectCommand command) { }
    static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    @BeforeAll void open() throws Exception {database=EffectAuditTestDatabase.open();registry=new JdbcInstanceStore(database.dataSource);}
    @AfterAll void close() throws Exception {if(database!=null)database.close();}
    Run fixture() {
        var owner=new Subject(SubjectKind.USER,"alíce-𝄞");
        var request=new StartRequest("audit-process-"+UUID.randomUUID(),owner,new DefinitionRef("agentic/effect-source-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",owner);
        var scope=new EffectScope(row.tenant(),row.id(),owner);return new Run(scope,EffectAuditProcessWorker.command(scope));
    }
    List<EffectAuditProducer.Retained> sources(Run run){return Transactions.inTransaction(database.dataSource,c->producer.forKey(c,run.command().key(),0,2));}
    long count(Run run,String table) throws SQLException {
        String query=table.equals("audit_source")?"select count(*) from effect.audit_source a join effect.event e on e.id=a.source_event_id where e.thread_name=? and e.node_id=? and e.idempotency_key=?":
                "select count(*) from effect."+table+" where thread_name=? and node_id=? and idempotency_key=?";
        try(var c=database.dataSource.getConnection();var q=c.prepareStatement(query)) {
            var key=run.command().key();q.setString(1,key.threadId().value());q.setString(2,key.nodeId());q.setString(3,key.idempotencyKey());
            try(var r=q.executeQuery()){assertTrue(r.next());return r.getLong(1);}
        }
    }
    String ack(long id) throws SQLException {
        try(var c=database.dataSource.getConnection();var q=c.prepareStatement("select ack_source_key from effect.audit_delivery where source_event_id=?")) {
            q.setLong(1,id);try(var r=q.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    @Test void deathBeforeSourceCommitRollsBackAllFactsAndFreshWorkersCreateOneSource() throws Exception {sourceDeath(SOURCE_BEFORE_COMMIT);}
    @Test void deathAfterSourceCommitRetainsOriginalDraftAcrossFreshWorkers() throws Exception {sourceDeath(SOURCE_AFTER_COMMIT);}
    private void sourceDeath(EffectAuditProcessWorker.Mode mode) throws Exception {
        var run=fixture();child(run,mode);
        for(var table:List.of("ledger","event","audit_scope","audit_source","audit_delivery"))assertEquals(mode.after?1:0,count(run,table),table);
        var committed=sources(run);child(run,PREPARE);var saved=sources(run);assertEquals(1,saved.size());
        if(mode.after)assertEquals(committed,saved,"actual committed original id/time/content must survive process death");
        child(run,PREPARE);assertEquals(saved,sources(run));
        for(var table:List.of("ledger","event","audit_scope","audit_source","audit_delivery"))assertEquals(1,count(run,table),table);
        assertNull(ack(saved.getFirst().source().sequence()),"preparing a source cannot acknowledge central audit");
    }
    @Test void deathBeforeLocalAckCommitLeavesPendingAndFreshWorkersAcknowledgeSameReceipt() throws Exception {ackDeath(ACK_BEFORE_COMMIT);}
    @Test void deathAfterLocalAckCommitRetainsAcknowledgmentAcrossFreshWorkers() throws Exception {ackDeath(ACK_AFTER_COMMIT);}
    private void ackDeath(EffectAuditProcessWorker.Mode mode) throws Exception {
        var run=fixture();child(run,PREPARE);var saved=sources(run);assertEquals(1,saved.size());var value=saved.getFirst();
        seedCentralData(value);child(run,mode);
        assertEquals(mode.after?value.source().key():null,ack(value.source().sequence()));
        child(run,ACK);assertEquals(value.source().key(),ack(value.source().sequence()));
        child(run,ACK);assertEquals(saved,sources(run));assertEquals(value.source().key(),ack(value.source().sequence()));
        assertEquals(1,count(run,"audit_delivery"));
        try(var c=database.dataSource.getConnection();var q=c.prepareStatement("""
            select (select count(*) from audit.event where chain_id=?),
              (select count(*) from audit.head_log where chain_id=?),
              (select count(*) from audit.source_binding where source_key=?)
            """)) {
            String chain=AuditDraftFingerprint.chainOf(value.draft());q.setString(1,chain);q.setString(2,chain);q.setString(3,value.source().key());
            try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(1,r.getLong(1));assertEquals(1,r.getLong(2));assertEquals(1,r.getLong(3));}
        }
    }
    /** Explicit central DATA fixture, two commits. Never a production atomic/idempotent appendOnce implementation. */
    void seedCentralData(EffectAuditProducer.Retained value) throws SQLException {
        var event=new AuditTrail(database.dataSource).append(value.draft());var s=value.source();
        assertEquals(s.contentFingerprint(),AuditDraftFingerprint.of(event));
        try(var c=database.dataSource.getConnection();var q=c.prepareStatement("""
            insert into audit.source_binding(source_key,schema_version,scope_fingerprint,key_fingerprint,transition,
              source_sequence,content_fingerprint,event_id,chain_id,chain_sequence,hash) values(?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            q.setString(1,s.key());q.setInt(2,s.schemaVersion());q.setString(3,s.scopeFingerprint());q.setString(4,s.keyFingerprint());
            q.setString(5,s.transition().name());q.setLong(6,s.sequence());q.setString(7,s.contentFingerprint());q.setString(8,event.id().value());
            q.setString(9,AuditDraftFingerprint.chainOf(event));q.setLong(10,event.sequence());q.setString(11,event.hash());q.executeUpdate();
        }
    }
    private void child(Run run,EffectAuditProcessWorker.Mode mode) throws Exception {
        // An owned unique directory contains all worker output/object stores; no cleanup of another session's files.
        Path parent=Path.of("target","process-logs").toAbsolutePath().normalize();Files.createDirectories(parent);
        Path directory=Files.createDirectory(parent.resolve("gpt-audit-"+UUID.randomUUID()));
        Path log=directory.resolve("worker.log"),arguments=directory.resolve("worker.args");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String absolute=String.join(File.pathSeparator,Arrays.stream(cp.split(Pattern.quote(File.pathSeparator),-1))
                .map(p->Path.of(p).toAbsolutePath().normalize().toString()).toList());
        String args="-cp\n"+quote(absolute)+"\n"+EffectAuditProcessWorker.class.getName()+"\n"+quote(database.name)+"\n"
                +quote(run.scope().tenant())+"\n"+quote(run.scope().instanceId().value())+"\n"+mode.name()+"\n";
        Files.writeString(arguments,args,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+arguments)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(45,TimeUnit.SECONDS),()->"owned audit worker timed out: "+log);
            assertEquals(mode.exit,child.exitValue(),()->diagnostics(log));
            if(mode.exit!=0)assertTrue(Files.readString(log).contains("owned audit worker halt "+mode.name()),()->diagnostics(log));
        } finally {
            if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS),"owned worker did not terminate");}
        }
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String diagnostics(Path log) {
        try(var lines=Files.lines(log)){return log+"\n"+String.join("\n",lines.limit(150).toList());}
        catch(Exception failed){return log.toString();}
    }
}
