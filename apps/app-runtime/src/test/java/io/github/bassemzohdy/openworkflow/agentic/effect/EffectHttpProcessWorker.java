package io.github.bassemzohdy.openworkflow.agentic.effect;

import com.sun.net.httpserver.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.postgresql.ds.PGSimpleDataSource;

/** TEST ONLY real HTTP server/client processes. Durable business tables exist only in our verified fresh database. */
public final class EffectHttpProcessWorker {
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--self-check")) {
            var manager=com.arjuna.ats.jta.TransactionManager.transactionManager();
            if(manager.getStatus()!=jakarta.transaction.Status.STATUS_NO_TRANSACTION)throw new IllegalStateException("ambient transaction");
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.stop(0);
            System.out.println("standalone HTTP and real transaction manager ready");return;
        }
        var ds=EffectAuditTestDatabase.dataSource(args[1]);EffectAuditTestDatabase.requireOwnedDatabase(args[1],ds);
        if(args[0].equals("SERVER"))server(ds,args[2]);
        else {
            var row=new JdbcInstanceStore(ds).find(args[2],Ulid.parse(args[3])).orElseThrow();
            var scope=new EffectScope(row.tenant(),row.id(),row.owner());var command=EffectHttpTestSupport.command(scope,false);
            var manager=com.arjuna.ats.jta.TransactionManager.transactionManager();
            if(manager.getStatus()!=jakarta.transaction.Status.STATUS_NO_TRANSACTION)throw new IllegalStateException("ambient transaction");
            var metrics=new SimpleMeterRegistry();
            try(var target=new EffectHttpTestSupport.Target(command,Integer.parseInt(args[4]),Duration.ofSeconds(5),args[0].equals("CLIENT_HALT"))) {
                var result=new AuditedEffects(ds,manager,(s,d)->EffectHttpTestSupport.receipt(ds,s,d),new EffectTelemetry(OpenTelemetry.noop()),metrics)
                        .invoke(new EffectInvocation(scope,command,null),target,(i,b)->{}); // Private composite authority is a protocol spy.
                if(result.state()!=EffectState.CONFIRMED)throw new IllegalStateException("HTTP client did not reconcile");
                System.out.println("owned HTTP client confirmed SAME_KEY");
            }finally{metrics.close();}
        }
    }
    private static void server(PGSimpleDataSource ds,String mode) throws Exception {
        var executor=Executors.newCachedThreadPool();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
        server.createContext("/effect",exchange->{
            try {handle(ds,mode,exchange);}
            catch(Exception failed){System.err.println("owned HTTP handler failed: "+failed.getClass().getSimpleName());try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);executor.shutdownNow();}));
        server.start();Files.writeString(Path.of("port"),Integer.toString(server.getAddress().getPort()),StandardOpenOption.CREATE_NEW);
        System.out.println("owned HTTP server ready "+mode);System.out.flush();new CountDownLatch(1).await();
    }
    private static void handle(PGSimpleDataSource ds,String mode,HttpExchange x) throws Exception {
        String key=x.getRequestHeaders().getFirst("X-Effect-Key"),fp=x.getRequestHeaders().getFirst("X-Request-Fingerprint"),op=x.getRequestHeaders().getFirst("X-Operation");
        boolean post=x.getRequestMethod().equals("POST");
        if(key==null || !key.matches("ef1-[0-9a-f]{64}") || !("charge".equals(op)||"refund".equals(op))
                || !(post||x.getRequestMethod().equals("GET"))){x.sendResponseHeaders(400,-1);return;}
        int amount=op.equals("refund")?-10:10;
        String expected=CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.effect.request.v1").text(op+"\t"+amount).toBytes());
        if(!expected.equals(fp) || (post && !new String(x.getRequestBody().readNBytes(20),StandardCharsets.UTF_8).equals(Integer.toString(amount)))){x.sendResponseHeaders(409,-1);return;}
        boolean found;
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var q=c.prepareStatement("insert into http_test_request(effect_key,method) values(?,?)")){q.setString(1,key);q.setString(2,x.getRequestMethod());q.executeUpdate();}
                if(post) {
                    int inserted;
                    try(var q=c.prepareStatement("insert into http_test_business(effect_key,request_fp,operation,amount) values(?,?,?,?) on conflict(effect_key) do nothing")) {
                        q.setString(1,key);q.setString(2,fp);q.setString(3,op);q.setInt(4,amount);inserted=q.executeUpdate();
                    }
                    if(inserted==1)try(var q=c.prepareStatement("update http_test_balance set balance=balance+? where account=1")){q.setInt(1,amount);if(q.executeUpdate()!=1)throw new IllegalStateException("fixture account missing");}
                }
                try(var q=c.prepareStatement("select request_fp,operation,amount from http_test_business where effect_key=?")) {
                    q.setString(1,key);try(var r=q.executeQuery()) {
                        found=r.next();if(found && (!fp.equals(r.getString(1))||!op.equals(r.getString(2))||amount!=r.getInt(3)))throw new IllegalStateException("fixture request conflict");
                    }
                }
                c.commit(); // Real business COMMIT, before any HTTP response or configured halt/wait.
            }finally{c.rollback();}
        }
        if(post && mode.equals("HALT_"+op.toUpperCase(java.util.Locale.ROOT))) {
            System.out.println("owned HTTP target halt AFTER_"+op.toUpperCase(java.util.Locale.ROOT)+"_COMMIT");System.out.flush();Runtime.getRuntime().halt(95);
        }
        if(post && mode.equals("WAIT_CHARGE") && op.equals("charge")) {
            Files.writeString(Path.of("committed"),key,StandardOpenOption.CREATE_NEW);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(Path.of("release"))) {
                if(System.nanoTime()>=until)throw new IllegalStateException("owned HTTP release timed out");Thread.sleep(20);
            }
        }
        if(!found){x.sendResponseHeaders(404,-1);return;}
        byte[] body=(key+"\t"+fp+"\tCONFIRMED").getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);
    }
}
