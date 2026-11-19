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

/** Actual HTTP -> verified caller -> bundled Cedar -> retained resolver -> PostgreSQL audit/outcome boundary. */
@QuarkusTest
@QuarkusTestResource(value=ManualEffectsApiResource.class, restrictToAnnotatedClass=true)
@Timeout(60)
class ManualEffectsApiTest extends ManualEffectsApiFixtures {
    @Test
    void operatorListsOnlyOpaqueReferencesAndExactRetriesApplyOnce() throws Exception {
        var f=unresolved(tenant(),"PRIVATE-KEY");var bearer=operator(f.tenant());String request=UlidGenerator.shared().next().value();
        var listed=given().auth().oauth2(bearer).get(f.path()+"/unknown");assertEquals(200,listed.statusCode(),listed.asString());
        assertEquals(List.of(f.effectId()),listed.jsonPath().getList("items.effectId"));
        assertFalse(listed.asString().contains("PRIVATE"));assertFalse(listed.asString().contains(f.command().key().threadId().value()));
        var answer=resolve(f,bearer,request,"CONFIRMED","TARGET_CONFIRMED");assertEquals(200,answer.statusCode(),answer.asString());
        assertEquals("CONFIRMED",answer.jsonPath().getString("state"));assertFalse(answer.asString().contains("PRIVATE"));
        assertEquals(answer.asString(),resolve(f,bearer,request,"CONFIRMED","TARGET_CONFIRMED").asString());
        assertEquals(409,resolve(f,bearer,request,"FAILED","TARGET_REJECTED").statusCode());
        assertEquals(409,resolve(f,token(f.tenant(),"different-operator",null,"oa.recover",Duration.ofMinutes(5)),request,"CONFIRMED","TARGET_CONFIRMED").statusCode());
        assertEquals(1,count("select count(*) from effect.manual_decision where request_id='"+request+"'"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied a join effect.manual_decision d using(decision_id) where d.request_id='"+request+"'"));
        assertEquals(0,given().auth().oauth2(bearer).get(f.path()+"/unknown").jsonPath().getList("items").size());
        assertEquals(0,count("select count(*) from public.flyway_schema_history where version in ('78','79','80','81','82','83')"));
    }

    @Test
    void tenantScopeHumanIdentityAndSignedScopeAreRequiredBeforeMutation() throws Exception {
        var f=unresolved(tenant(),"security");String request=UlidGenerator.shared().next().value();
        assertEquals(401,given().get(f.path()+"/unknown").statusCode());
        assertEquals(403,resolve(f,token(f.tenant(),"operator",null,"oa.operate",Duration.ofMinutes(5)),request,"CONFIRMED","TARGET_CONFIRMED").statusCode());
        assertEquals(403,resolve(f,token(f.tenant(),"service","SERVICE","oa.recover",Duration.ofMinutes(5)),request,"CONFIRMED","TARGET_CONFIRMED").statusCode());
        assertEquals(404,resolve(f,operator(tenant()),request,"CONFIRMED","TARGET_CONFIRMED").statusCode());
        assertEquals(400,resolve(f,operator(f.tenant()),request,"CONFIRMED","TARGET_REJECTED").statusCode());
        assertEquals(400,resolve(f,operator(f.tenant()),"not-a-ulid","CONFIRMED","TARGET_CONFIRMED").statusCode());
        assertEquals(0,count("select count(*) from effect.manual_decision where request_id='"+request+"'"));
        assertEquals(200,resolve(f,operator(f.tenant()),request,"FAILED","TARGET_REJECTED").statusCode());
    }

    @Test
    void pagedUnknownEffectsStayInTheVerifiedInstanceAndMalformedCursorRefuses() throws Exception {
        var f=unresolved(tenant(),"page-first");var bearer=operator(f.tenant());
        var second=effect(f.tenant(),f.instance(),"page-second");
        var third=effect(f.tenant(),f.instance(),"page-third");
        // Different instances in the same tenant are not included in this instance's page.
        unresolved(f.tenant(),"other-instance");
        var collected=new ArrayList<String>();String cursor=null;
        for(int page=0;page<3;page++) {
            var query=given().auth().oauth2(bearer).queryParam("limit",1);
            if(cursor!=null)query.queryParam("cursor",cursor);
            var result=query.get(f.path()+"/unknown");assertEquals(200,result.statusCode(),result.asString());
            var ids=result.jsonPath().<String>getList("items.effectId");assertEquals(1,ids.size());collected.addAll(ids);
            cursor=result.jsonPath().getString("nextCursor");
            if(page<2)assertNotNull(cursor);else assertNull(cursor);
        }
        assertEquals(new TreeSet<>(List.of(f.effectId(),second.effectId(),third.effectId())),new TreeSet<>(collected));
        assertEquals(new ArrayList<>(new TreeSet<>(collected)),collected);
        assertEquals(400,given().auth().oauth2(bearer).queryParam("cursor","invalid").get(f.path()+"/unknown").statusCode());
        assertEquals(400,given().auth().oauth2(bearer).queryParam("limit",0).get(f.path()+"/unknown").statusCode());
    }

    @Test
    void failedCentralAuditCannotApplyAndRetryUsesTheSameRetainedDecision() throws Exception {
        var f=unresolved(tenant(),"audit-failure");var bearer=operator(f.tenant());String request=UlidGenerator.shared().next().value();
        String name="api_audit_"+UUID.randomUUID().toString().replace("-","");
        sql("create function public."+name+"() returns trigger language plpgsql as $$ begin if new.event_type='effect.manual-resolution-requested' and new.resource_id='"+f.effectId()+"' then raise exception 'injected audit failure'; end if; return new; end $$");
        sql("create trigger "+name+" before insert on audit.event for each row execute function public."+name+"()");
        try {
            assertEquals(503,resolve(f,bearer,request,"CONFIRMED","TARGET_CONFIRMED").statusCode());
            assertEquals(0,count("select count(*) from effect.manual_decision_applied a join effect.manual_decision d using(decision_id) where d.request_id='"+request+"'"));
        } finally { sql("drop trigger "+name+" on audit.event;drop function public."+name+"()"); }
        assertEquals(200,resolve(f,bearer,request,"CONFIRMED","TARGET_CONFIRMED").statusCode());
        assertEquals(1,count("select count(*) from effect.manual_decision where request_id='"+request+"'"));
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested' and resource_id='"+f.effectId()+"'"));
    }

    @Test
    void concurrentIdenticalHttpRequestsRetainOneDecisionAndOneAppliedOutcome() throws Exception {
        var f=unresolved(tenant(),"concurrent");var bearer=operator(f.tenant());String request=UlidGenerator.shared().next().value();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->resolve(f,bearer,request,"CONFIRMED","TARGET_CONFIRMED"));
            var b=workers.submit(()->resolve(f,bearer,request,"CONFIRMED","TARGET_CONFIRMED"));
            assertEquals(200,a.get(20,TimeUnit.SECONDS).statusCode());assertEquals(200,b.get(20,TimeUnit.SECONDS).statusCode());
        }
        assertEquals(1,count("select count(*) from effect.manual_decision where request_id='"+request+"'"));
        assertEquals(1,count("select count(*) from effect.manual_decision_applied a join effect.manual_decision d using(decision_id) where d.request_id='"+request+"'"));
    }

    @Test
    void expiryDuringCentralAuditWithholdsMutationAndAFreshCallerCanRetry() throws Exception {
        var f=unresolved(tenant(),"expiry-during-audit");String request=UlidGenerator.shared().next().value();
        String name="api_slow_"+UUID.randomUUID().toString().replace("-","");
        sql("create function public."+name+"() returns trigger language plpgsql as $$ begin if new.event_type='effect.manual-resolution-requested' and new.resource_id='"+f.effectId()+"' then perform pg_sleep(6); end if; return new; end $$");
        sql("create trigger "+name+" before insert on audit.event for each row execute function public."+name+"()");
        String bearer=token(f.tenant(),"operator",null,"oa.recover",Duration.ofSeconds(5));
        try {
            var refused=resolve(f,bearer,request,"CONFIRMED","TARGET_CONFIRMED");assertEquals(401,refused.statusCode(),refused.asString());
            assertEquals(0,count("select count(*) from effect.manual_decision_applied a join effect.manual_decision d using(decision_id) where d.request_id='"+request+"'"));
            assertFalse(refused.asString().contains(bearer));
        } finally {sql("drop trigger "+name+" on audit.event;drop function public."+name+"()");}
        assertEquals(200,resolve(f,operator(f.tenant()),request,"CONFIRMED","TARGET_CONFIRMED").statusCode());
        assertEquals(1,count("select count(*) from audit.event where event_type='effect.manual-resolution-requested' and resource_id='"+f.effectId()+"'"));
    }
}
