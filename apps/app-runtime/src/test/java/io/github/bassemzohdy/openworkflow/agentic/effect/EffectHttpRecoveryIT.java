package io.github.bassemzohdy.openworkflow.agentic.effect;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.IdempotentAuditSink;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;

/** Actual HTTP, target business commits and owned JVM deaths. NOT production adapter/auth/Flow/atomic audit proof. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EffectHttpRecoveryIT {
    EffectAuditTestDatabase db;
    JdbcInstanceStore registry;
    JdbcEffectLedger ledger;
    final EffectAuditProducer producer=new EffectAuditProducer();
    final jakarta.transaction.TransactionManager manager=com.arjuna.ats.jta.TransactionManager.transactionManager();
    static Instant now(){return Instant.now().truncatedTo(ChronoUnit.MICROS);}
    record Fixture(EffectScope scope,EffectCommand command) {
        EffectInvocation invocation(){return new EffectInvocation(scope,command,null);}
        EffectCommand undo(){return EffectHttpTestSupport.command(scope,true);}
        EffectInvocation compensation(){return new EffectInvocation(scope,undo(),command.key());}
    }
    @BeforeAll void createOnlyOwnedDatabase() throws Exception {
        assertEquals(jakarta.transaction.Status.STATUS_NO_TRANSACTION,manager.getStatus());
        db=EffectAuditTestDatabase.open();registry=new JdbcInstanceStore(db.dataSource);ledger=new JdbcEffectLedger(db.dataSource,manager,producer);
        try(var c=db.dataSource.getConnection()) {
            EffectAuditTestDatabase.sql(c,"""
                create table http_test_business(effect_key text primary key,request_fp text not null,operation text not null,amount integer not null);
                create table http_test_request(id bigserial primary key,effect_key text not null,method text not null);
                create table http_test_balance(account integer primary key check(account=1),balance integer not null);
                insert into http_test_balance values(1,0)
                """);
        }
    }
    @AfterAll void dropOnlyOwnedDatabase() throws Exception {if(db!=null)db.close();}
    Fixture fixture() {
        var owner=new Subject(SubjectKind.USER,"http-alíce-𝄞");
        var request=new StartRequest("http-effect-"+UUID.randomUUID(),owner,new DefinitionRef("agentic/http-recovery-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();
        registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",owner);
        var scope=new EffectScope(row.tenant(),row.id(),owner);return new Fixture(scope,EffectHttpTestSupport.command(scope,false));
    }
    void end(Fixture f) {
        var row=registry.find(f.scope().tenant(),f.scope().instanceId()).orElseThrow();
        registry.replace(row.successor(now(),InstanceStatus.FAILED,row.engineInstanceId(),1,"test-ended",null),row.version(),"test.failed",row.owner());
    }
    Effect invoke(EffectInvocation invocation,EffectHttpTestSupport.Target target) {return invoke(invocation,target,(s,d)->EffectHttpTestSupport.receipt(db.dataSource,s,d));}
    Effect invoke(EffectInvocation invocation,EffectHttpTestSupport.Target target,IdempotentAuditSink sink) {
        var metrics=new SimpleMeterRegistry();
        try {
            return new AuditedEffects(db.dataSource,manager,sink,new EffectTelemetry(OpenTelemetry.noop()),metrics)
                    .invoke(invocation,target,(i,b)->{}); // Explicit private authority protocol spy. Actual DB registry guard remains real.
        }finally{metrics.close();}
    }
    EffectHttpTestSupport.Target target(EffectCommand command,Child server){return new EffectHttpTestSupport.Target(command,server.port(),Duration.ofSeconds(5),false);}
    List<EffectAuditProducer.Retained> history(EffectCommand command) {
        return Transactions.inTransaction(db.dataSource,c->producer.forKey(c,command.key(),0,128));
    }
    void fullyAcknowledged(EffectCommand command) throws Exception {
        var history=history(command);assertFalse(history.isEmpty());
        for(var retained:history) {
            EffectAuditProcessWorker.fixtureReceipt(db.dataSource,retained.source(),retained.draft());
            try(var c=db.dataSource.getConnection();var q=c.prepareStatement("select ack_source_key from effect.audit_delivery where source_event_id=?")) {
                q.setLong(1,retained.source().sequence());try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(retained.source().key(),r.getString(1));}
            }
        }
    }
    void business(EffectCommand command,int posts,int gets,int operations) throws Exception {
        try(var c=db.dataSource.getConnection();var q=c.prepareStatement("""
            select (select count(*) from http_test_request where effect_key=? and method='POST'),
              (select count(*) from http_test_request where effect_key=? and method='GET'),
              (select count(*) from http_test_business where effect_key=?),
              (select balance from http_test_balance where account=1),
              (select coalesce(sum(amount),0) from http_test_business)
            """)) {
            for(int i=1;i<=3;i++)q.setString(i,command.key().idempotencyKey());
            try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(posts,r.getLong(1));assertEquals(gets,r.getLong(2));assertEquals(operations,r.getLong(3));assertEquals(r.getLong(5),r.getLong(4),"atomic business account must equal all committed operations");}
        }
    }
    @Test void auditRefusalMakesZeroHttpRequests() throws Exception {
        var f=fixture();try(var server=server("NORMAL");var target=target(f.command(),server)) {
            assertThrows(IllegalStateException.class,()->invoke(f.invocation(),target,(s,d)->{throw new IllegalStateException("test audit dependency unavailable");}));
            business(f.command(),0,0,0);assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());
        }
    }
    @Test void registryClosureDuringAuditMakesZeroHttpRequests() throws Exception {
        var f=fixture();try(var server=server("NORMAL");var target=target(f.command(),server)) {
            assertThrows(IllegalStateException.class,()->invoke(f.invocation(),target,(s,d)->{
                var receipt=EffectHttpTestSupport.receipt(db.dataSource,s,d);if(s.transition()==AuditSource.Transition.DISPATCH)end(f);return receipt;
            }));
            business(f.command(),0,0,0);assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());
        }
    }
    @Test void targetDeathAfterChargeCommitRecoversByHttpLookupWithoutSecondPost() throws Exception {
        var f=fixture();try(var server=server("HALT_CHARGE");var target=target(f.command(),server)) {
            assertEquals(EffectState.INTENDED,invoke(f.invocation(),target).state());server.exit(95,"owned HTTP target halt AFTER_CHARGE_COMMIT");business(f.command(),1,0,1);
        }
        var original=history(f.command());
        try(var server=server("NORMAL");var target=target(f.command(),server)) {
            assertEquals(EffectState.CONFIRMED,invoke(f.invocation(),target).state());business(f.command(),1,1,1);
            assertEquals(original,history(f.command()).subList(0,original.size()));fullyAcknowledged(f.command());
        }
    }
    @Test void targetDeathAfterRefundCommitRetainsOriginalUntilHttpLookupConfirmsUndo() throws Exception {
        var f=fixture();try(var server=server("NORMAL");var target=target(f.command(),server)) {assertEquals(EffectState.CONFIRMED,invoke(f.invocation(),target).state());}
        try(var server=server("HALT_REFUND");var target=target(f.undo(),server)) {
            assertEquals(EffectState.CONFIRMED,invoke(f.compensation(),target).state());server.exit(95,"owned HTTP target halt AFTER_REFUND_COMMIT");
            assertEquals(EffectState.INTENDED,ledger.find(f.undo().key()).orElseThrow().state());business(f.undo(),1,0,1);
        }
        try(var server=server("NORMAL");var target=target(f.undo(),server)) {
            assertEquals(EffectState.COMPENSATED,invoke(f.compensation(),target).state());business(f.command(),1,0,1);business(f.undo(),1,1,1);
            fullyAcknowledged(f.command());fullyAcknowledged(f.undo());
        }
    }
    @Test void registryClosureAfterHttpCommitRetainsAuditedOutcomeAndRefusesPrivateResult() throws Exception {
        var f=fixture();var executor=Executors.newSingleThreadExecutor();
        try(var server=server("WAIT_CHARGE");var target=target(f.command(),server)) {
            try {
                var result=executor.submit(()->invoke(f.invocation(),target));server.await("committed");end(f);server.release();
                var failed=assertThrows(ExecutionException.class,()->result.get(15,TimeUnit.SECONDS));assertInstanceOf(IllegalStateException.class,failed.getCause());
                business(f.command(),1,0,1);assertEquals(EffectState.CONFIRMED,ledger.find(f.command().key()).orElseThrow().state());fullyAcknowledged(f.command());
                assertThrows(IllegalStateException.class,()->invoke(f.invocation(),target));business(f.command(),1,0,1);
            }finally{server.release();}
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void clientDeathAfterHttpReplyRecoversInFreshClientProcessWithoutSecondPost() throws Exception {
        var f=fixture();try(var server=server("NORMAL")) {
            try(var client=child("CLIENT_HALT",db.name,f.scope().tenant(),f.scope().instanceId().value(),Integer.toString(server.port()))) {
                client.exit(96,"owned HTTP client halt AFTER_REPLY_BEFORE_OUTCOME");
            }
            assertEquals(EffectState.INTENDED,ledger.find(f.command().key()).orElseThrow().state());business(f.command(),1,0,1);var original=history(f.command());
            try(var client=child("CLIENT",db.name,f.scope().tenant(),f.scope().instanceId().value(),Integer.toString(server.port()))) {client.exit(0,"owned HTTP client confirmed SAME_KEY");}
            assertEquals(EffectState.CONFIRMED,ledger.find(f.command().key()).orElseThrow().state());business(f.command(),1,1,1);
            assertEquals(original,history(f.command()).subList(0,original.size()));fullyAcknowledged(f.command());
        }
    }
    @Test void httpTimeoutAfterTargetCommitStaysUnknownUntilReadOnlyLookup() throws Exception {
        var f=fixture();var executor=Executors.newSingleThreadExecutor();
        try(var server=server("WAIT_CHARGE");var target=new EffectHttpTestSupport.Target(f.command(),server.port(),Duration.ofSeconds(3),false)) {
            try {
                var result=executor.submit(()->invoke(f.invocation(),target));server.await("committed");assertFalse(target.timedOut(),"actual target commit must precede the HTTP timeout");
                assertEquals(EffectState.INTENDED,result.get(12,TimeUnit.SECONDS).state());assertTrue(target.timedOut(),"require actual JDK HTTP timeout, not another exception");
                business(f.command(),1,0,1);fullyAcknowledged(f.command());
                assertTrue(history(f.command()).stream().anyMatch(v->v.source().transition()==AuditSource.Transition.UNKNOWN));
            }finally{server.release();}
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        try(var server=server("NORMAL");var target=target(f.command(),server)) {
            assertEquals(EffectState.CONFIRMED,invoke(f.invocation(),target).state());business(f.command(),1,1,1);fullyAcknowledged(f.command());
        }
    }
    Child server(String mode) throws Exception {
        var child=child("SERVER",db.name,mode);
        try{child.await("port");return child;}catch(Exception|Error failed){try{child.close();}catch(Exception cleanup){failed.addSuppressed(cleanup);}throw failed;}
    }
    Child child(String...args) throws Exception {
        Path parent=Path.of("target","process-logs").toAbsolutePath().normalize();Files.createDirectories(parent);
        Path directory=Files.createDirectory(parent.resolve("gpt-http-"+UUID.randomUUID()));
        Path log=directory.resolve(args[0].equals("SERVER")?"http-server.log":"http-client.log"),arguments=directory.resolve("http.args");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String absolute=String.join(File.pathSeparator,Arrays.stream(cp.split(Pattern.quote(File.pathSeparator),-1)).map(p->Path.of(p).toAbsolutePath().normalize().toString()).toList());
        var content=new StringBuilder("-cp\n").append(quote(absolute)).append('\n').append(EffectHttpProcessWorker.class.getName()).append('\n');
        for(String arg:args)content.append(quote(arg)).append('\n');
        Files.writeString(arguments,content,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+arguments)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new Child(process,directory,log);
    }
    record Child(Process process,Path directory,Path log) implements AutoCloseable {
        int port(){try{return Integer.parseInt(Files.readString(directory.resolve("port")));}catch(Exception failed){throw new IllegalStateException("owned server port unavailable",failed);}}
        void await(String file) throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!Files.exists(directory.resolve(file))) {
                if(!process.isAlive() || System.nanoTime()>=until)fail("owned HTTP worker did not publish "+file+": "+diagnostics());Thread.sleep(20);
            }
        }
        void release() throws Exception {if(!Files.exists(directory.resolve("release")))Files.writeString(directory.resolve("release"),"release",StandardOpenOption.CREATE_NEW);}
        void exit(int code,String marker) throws Exception {
            assertTrue(process.waitFor(40,TimeUnit.SECONDS),()->"owned HTTP worker timed out: "+log);assertEquals(code,process.exitValue(),this::diagnostics);
            assertTrue(Files.readString(log).contains(marker),this::diagnostics);
        }
        String diagnostics(){try(var lines=Files.lines(log)){return log+"\n"+String.join("\n",lines.limit(100).toList());}catch(Exception ignored){return log.toString();}}
        public void close() throws Exception {
            if(process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS),"owned HTTP worker did not terminate");}}
        }
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}
