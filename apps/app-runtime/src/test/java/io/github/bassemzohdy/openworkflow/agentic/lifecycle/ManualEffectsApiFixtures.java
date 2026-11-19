package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.effect.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Shared JDBC fixtures; no authority or target is inferred from their retained identifiers. */
abstract class ManualEffectsApiFixtures {
    @Inject DataSource dataSource;
    @Inject TransactionManager transactions;
    record Fixture(String tenant, Ulid instance, EffectCommand command, String effectId) {
        String path() { return "/operations/instances/" + instance + "/effects"; }
    }

    Fixture unresolved(String tenant, String occurrence) {
        var owner = new Subject(SubjectKind.USER,"workflow-owner");
        var request = new StartRequest(tenant,owner,new DefinitionRef("agentic/manual-api","1"),Map.of(),UUID.randomUUID().toString());
        var store = new JdbcInstanceStore(dataSource); var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var row = store.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now),"api.fixture").record();
        store.replace(row.successor(now,InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"fixture.running",owner);
        return effect(tenant,row.id(),occurrence);
    }

    Fixture effect(String tenant, Ulid instance, String occurrence) {
        var scope = new EffectScope(tenant,instance,new Subject(SubjectKind.USER,"workflow-owner"));
        var command = scope.command("PRIVATE-NODE",occurrence,"PRIVATE-TARGET","{\"private\":true}");
        var ledger = new JdbcEffectLedger(dataSource,transactions,new EffectAuditProducer());
        ledger.prepareScoped(scope,command,Duration.ZERO,Instant.now()); ledger.markAttempted(command.key());
        ledger.requireManualResolution(command.key(),"effect.outcome.unknown");
        var encoder = new CanonicalEncoder("oa.effect.audit-key.v1");
        encoder.text(command.key().threadId().value()).text(command.key().nodeId()).text(command.key().idempotencyKey());
        return new Fixture(tenant,instance,command,"efp1-"+CanonicalEncoder.sha256Hex(encoder.toBytes()));
    }

    static String token(String tenant, String subject, String kind, String scopes, Duration lifetime) throws Exception {
        var claims = ManualEffectsApiResource.idp.baseClaims(subject,null,lifetime);
        claims.put("tenant",tenant); claims.put("aud","oa-api"); claims.put("scope",scopes);
        if (kind != null) claims.put("sub_kind",kind);
        return ManualEffectsApiResource.idp.signClaims(claims);
    }
    static String operator(String tenant) throws Exception { return token(tenant,"operator",null,"oa.recover",Duration.ofMinutes(5)); }
    static Response resolve(Fixture f, String bearer, String request, String outcome, String reason) {
        return given().auth().oauth2(bearer).header("Idempotency-Key",request).contentType("application/json")
                .body(Map.of("outcome",outcome,"reason",reason)).post(f.path()+"/"+f.effectId()+"/resolve");
    }
    long count(String sql) throws Exception {
        try (var c=dataSource.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)) { r.next();return r.getLong(1); }
    }
    void sql(String sql) throws Exception { try(var c=dataSource.getConnection();var s=c.createStatement()){s.execute(sql);} }
    String tenant() { return "manual-api-"+UUID.randomUUID(); }

}
