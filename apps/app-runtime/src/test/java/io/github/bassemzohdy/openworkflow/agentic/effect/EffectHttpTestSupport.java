package io.github.bassemzohdy.openworkflow.agentic.effect;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditTrail;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import javax.sql.DataSource;

/** TEST ONLY: loopback transport and separate-commit central DATA receipts, never production authority/appendOnce. */
final class EffectHttpTestSupport {
    static EffectCommand command(EffectScope scope,boolean refund) {
        return scope.command(refund?"refund":"charge","http-item/é/1",refund?"http-refund-v1":"http-charge-v1",refund?"refund\t-10":"charge\t10");
    }
    static final class Target implements EffectTarget,AutoCloseable {
        private final EffectCommand command;
        private final URI endpoint;
        private final Duration timeout;
        private final boolean halt;
        private volatile boolean timedOut;
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).version(HttpClient.Version.HTTP_1_1).build();
        Target(EffectCommand command,int port,Duration timeout,boolean halt) {
            this.command=command;this.endpoint=URI.create("http://127.0.0.1:"+port+"/effect");this.timeout=timeout;this.halt=halt;
        }
        public String target(){return command.target();}
        // ZERO retention guarantee: the durable core must query and may never resend after an uncertain reply.
        public Duration replayWindow(){return Duration.ZERO;}
        public Reply execute(String key) throws Exception {
            var reply=request(key,true);
            if(halt && reply.outcome()==Outcome.CONFIRMED) {
                System.out.println("owned HTTP client halt AFTER_REPLY_BEFORE_OUTCOME");System.out.flush();Runtime.getRuntime().halt(96);
            }
            return reply;
        }
        public Reply lookup(String key) throws Exception{return request(key,false);}
        boolean timedOut(){return timedOut;}
        private Reply request(String key,boolean post) throws Exception {
            if(!key.equals(command.key().idempotencyKey()))throw new IllegalArgumentException("test transport key differs");
            boolean refund=command.target().equals("http-refund-v1");
            var builder=HttpRequest.newBuilder(endpoint).timeout(timeout).header("X-Effect-Key",key)
                    .header("X-Request-Fingerprint",command.requestFingerprint()).header("X-Operation",refund?"refund":"charge");
            if(post)builder.POST(HttpRequest.BodyPublishers.ofString(refund?"-10":"10"));else builder.GET();
            HttpResponse<String> response;
            try{response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
            catch(HttpTimeoutException failure){timedOut=true;throw failure;}
            if(response.statusCode()==404 && !post)return new Reply(Outcome.UNKNOWN,null);
            if(response.statusCode()!=200 || !response.body().equals(key+"\t"+command.requestFingerprint()+"\tCONFIRMED"))
                throw new IllegalStateException("test target reply unconfirmed");
            return new Reply(Outcome.CONFIRMED,"fixture operation confirmed");
        }
        public void close(){client.close();}
    }
    /** The REAL AuditTrail append and the binding INSERT commit SEPARATELY. This cannot prove atomic appendOnce. */
    static AuditReceipt receipt(DataSource ds,AuditSource source,AuditEvent draft) {
        try(var c=ds.getConnection()) {
            try(var q=c.prepareStatement("select 1 from audit.source_binding where source_key=?")) {
                q.setString(1,source.key());try(var r=q.executeQuery()) {
                    if(r.next())return EffectAuditProcessWorker.fixtureReceipt(ds,source,draft);
                }
            }
            var event=new AuditTrail(ds).append(draft);
            var receipt=new AuditReceipt(source,event.id(),AuditDraftFingerprint.chainOf(event),event.sequence(),event.hash(),AuditDraftFingerprint.of(event));
            receipt.requireMatches(source,draft);
            try(var q=c.prepareStatement("""
                insert into audit.source_binding(source_key,schema_version,scope_fingerprint,key_fingerprint,transition,
                  source_sequence,content_fingerprint,event_id,chain_id,chain_sequence,hash) values(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
                q.setString(1,source.key());q.setInt(2,source.schemaVersion());q.setString(3,source.scopeFingerprint());q.setString(4,source.keyFingerprint());
                q.setString(5,source.transition().name());q.setLong(6,source.sequence());q.setString(7,source.contentFingerprint());q.setString(8,event.id().value());
                q.setString(9,receipt.chainId());q.setLong(10,event.sequence());q.setString(11,event.hash());q.executeUpdate();
            }
            return receipt;
        }catch(SQLException failed){throw new StoreUnavailableException("central HTTP-test DATA fixture failed",failed);}
    }
}
