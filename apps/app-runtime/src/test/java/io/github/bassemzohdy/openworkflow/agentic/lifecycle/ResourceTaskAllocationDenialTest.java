package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import java.sql.*;
import java.time.Duration;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

/** Negative admission DATA tests on real V77 plus proposed V78; no production authority or activation. */
class ResourceTaskAllocationDenialTest {
    @FunctionalInterface interface Case { void run(ResourceTaskAllocationMigrationTest s, Fixture f,
                                                  ResourceTaskAllocation a) throws Exception; }
    static void isolated(Case test) throws Exception {
        var s=new ResourceTaskAllocationMigrationTest();
        try {s.create();s.apply();var f=fixture(s.db,"a".repeat(64));test.run(s,f,s.candidate(f));}
        finally {s.close();}
    }
    static void refused(ResourceTaskAllocationMigrationTest s, ResourceTaskAllocation a, String message) throws Exception {
        var failure=assertThrows(SQLException.class,()->insert(s,a));
        assertEquals("23514",failure.getSQLState());assertTrue(failure.getMessage().contains(message),failure.getMessage());
        assertEquals(0,count(s.db.owner,"select count(*) from task.resource_task_allocation"));
        assertEquals(0,count(s.db.owner,"select count(*) from task.resource_execution"));
    }
    static void insert(ResourceTaskAllocationMigrationTest s,ResourceTaskAllocation a) throws Exception {
        try(var c=s.db.owner.getConnection();var q=c.prepareStatement("""
            insert into task.resource_task_allocation(allocation_key,call_key,call_fingerprint,parent_task_id,
              contract_id,contract,contract_fingerprint,model_profile,profile_fingerprint,fingerprint)
            values(?,?,?,?,?,?::jsonb,?,?::jsonb,?,?)
            """)) {
            q.setString(1,a.key());q.setString(2,a.scope().key());q.setString(3,a.scope().fingerprint());
            q.setString(4,a.parentTaskId()==null?null:a.parentTaskId().value());q.setString(5,a.contract().id().value());
            q.setString(6,ResourceTaskAllocationMigrationTest.contract(a));q.setString(7,AgentTaskContractFingerprint.of(a.contract()));
            q.setString(8,ResourceTaskAllocationMigrationTest.profile(a));q.setString(9,a.modelProfile().fingerprint());
            q.setString(10,a.fingerprint());q.executeUpdate();
        }
    }
    static AgentTaskRecord parent(ResourceTaskAllocationMigrationTest s,Fixture f,ResourceTaskAllocation a) {
        var t=a.contract();var p=new AgentTaskContract(UlidGenerator.shared().next(),"retained parent",t.permittedTools(),
            t.permittedDataScopes(),RiskTier.MEDIUM,new ExecutionLimits(10,Duration.ofSeconds(600),10000),
            t.requestingSubject(),f.binding().issuingGrantId());
        var row=new AgentTaskRecord(p.id(),a.scope().tenant(),a.scope().owner(),a.scope().instanceId().value(),
            ThreadId.derive(a.scope().tenant(),a.scope().owner().id(),a.scope().instanceId().value()),p,AgentTaskStatus.PENDING,0);
        new JdbcTaskStore(s.db.owner).insert(row);return row;
    }
    @TestFactory Stream<DynamicTest> currentRegistryAttributionIsRequiredAfterMatchingCallMetadata() {
        var cases=new LinkedHashMap<String,String>();
        cases.put("owner-kind","owner_kind='AGENT'");cases.put("owner-id","owner_id='different'");
        cases.put("engine","engine_instance_id='different'");cases.put("definition","definition_id='other/definition'");
        cases.put("version","definition_version='999'");cases.put("cancelled","status='CANCEL_REQUESTED'");
        return cases.entrySet().stream().map(e->DynamicTest.dynamicTest(e.getKey(),()->isolated((s,f,a)->{
            execute(s.db.owner,"update task.instance set "+e.getValue());refused(s,a,"registry or occurrence differs");
        })));
    }
    @TestFactory Stream<DynamicTest> retainedParentMustMatchFullOwnershipThreadAndLifetime() {
        var cases=new LinkedHashMap<String,String>();
        cases.put("owner-kind","owner_kind='AGENT'");cases.put("owner-id","owner_id='different'");
        cases.put("tenant","tenant='different'");cases.put("closed","status='COMPLETED'");
        cases.put("thread","thread_name='ws1-'||repeat('0',64)");
        cases.put("future","created_at=clock_timestamp()+interval '1 hour'");
        cases.put("expired","created_at=clock_timestamp()-interval '1 hour'");
        cases.put("contract-owner","contract=jsonb_set(contract,'{requestingSubject,id}','\"different\"')");
        return cases.entrySet().stream().map(e->DynamicTest.dynamicTest(e.getKey(),()->isolated((s,f,a)->{
            var p=parent(s,f,a);execute(s.db.owner,"update task.agent_task set "+e.getValue());
            refused(s,new ResourceTaskAllocation(a.scope(),p.taskId(),a.contract(),a.modelProfile()),
                e.getKey().equals("expired")?"original lifetime is exhausted":"parent task differs or is closed");
        })));
    }
    @TestFactory Stream<DynamicTest> childCannotWidenAnyActualParentLimitOrAllowList() {
        var cases=new LinkedHashMap<String,String>();
        cases.put("tools","contract=jsonb_set(contract,'{permittedTools}','[]')");
        cases.put("data","contract=jsonb_set(contract,'{permittedDataScopes}','[]')");
        cases.put("iterations","contract=jsonb_set(contract,'{limits,maxIterations}','1')");
        cases.put("cost","contract=jsonb_set(contract,'{limits,maxCostMicros}','1000')");
        cases.put("wall","contract=jsonb_set(contract,'{limits,maxWallClockSeconds}','60')");
        return cases.entrySet().stream().map(e->DynamicTest.dynamicTest(e.getKey(),()->isolated((s,f,a)->{
            var p=parent(s,f,a);execute(s.db.owner,"update task.agent_task set "+e.getValue());
            refused(s,new ResourceTaskAllocation(a.scope(),p.taskId(),a.contract(),a.modelProfile()),"widens actual parent task/grant");
        })));
    }
    @Test void validRetainedParentAndExactlyNarrowedChildStillAllocate() throws Exception {
        isolated((s,f,a)->{var p=parent(s,f,a);insert(s,new ResourceTaskAllocation(a.scope(),p.taskId(),a.contract(),a.modelProfile()));
            assertEquals(1,count(s.db.owner,"select count(*) from task.resource_task_allocation"));
            assertEquals(p,new JdbcTaskStore(s.db.owner).find(p.taskId()).orElseThrow());});
    }
    @Test void elapsedRegistryWaitDeadlineRefusesEvenWithLiveGrants() throws Exception {
        isolated((s,f,a)->{execute(s.db.owner,"update task.instance set status='WAITING',wait_deadline=clock_timestamp()-interval '1 second'");
            refused(s,a,"original lifetime is exhausted");});
    }
    static DelegationGrants grants(ResourceTaskAllocationMigrationTest s) {
        var clock=Clock.systemUTC();var ids=UlidGenerator.shared();
        return new DelegationGrants(new DelegationGrantStore(s.db.owner),new IdentityAudit(e->{},clock,ids),clock,ids,Duration.ofHours(2));
    }
    @TestFactory Stream<DynamicTest> actualRevocationOfEitherChosenOrIssuingGrantRefuses() {
        return Stream.of(false,true).map(root->DynamicTest.dynamicTest(root?"issuing ancestor":"chosen child",()->isolated((s,f,a)->{
            grants(s).revoke(root?f.binding().issuingGrantId():a.contract().delegationGrantId(),a.scope().owner(),"denial test",null);
            refused(s,a,"grant ancestry is not current");
        })));
    }
    @Test void aDifferentActualGrantChainCannotBorrowTheExistingCallBinding() throws Exception {
        isolated((s,f,a)->{var other=fixture(s.db,"a".repeat(64));var foreign=s.candidate(other);var t=a.contract();
            var changed=new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),
                t.limits(),t.requestingSubject(),foreign.contract().delegationGrantId());
            refused(s,new ResourceTaskAllocation(a.scope(),null,changed,a.modelProfile()),"not an actual issuing/narrowed chain");});
    }
    @Test void actualGrantAgentIdentityMustMatchTheModelProfileAgent() throws Exception {
        isolated((s,f,a)->{var p=a.modelProfile();var wrong=new ApprovedModelProfile(p.id(),p.version(),p.configurationFingerprint(),
            new io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject(
                io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind.AGENT,"different-agent"),
            p.modelId(),p.maxModelCalls(),p.perCallCeilingMicros(),p.maxCallTime(),p.maxOutputTokens());
            refused(s,new ResourceTaskAllocation(a.scope(),null,a.contract(),wrong),"model agent differs");});
    }
    @Test void realDatabaseClockExpiryOfANewGrantRefusesWithoutFabricatedStatus() throws Exception {
        isolated((s,f,a)->{var root=new DelegationGrantStore(s.db.owner).find(f.binding().issuingGrantId()).orElseThrow();
            var expiry=databaseNow(s.db.owner).plusSeconds(2);
            var child=grants(s).delegate(root.id(),root.agent(),a.modelProfile().agent(),Set.of("oa.agent.run"),expiry,null);
            var t=a.contract();var task=new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),
                t.limits(),t.requestingSubject(),child.id());
            long bound=System.nanoTime()+Duration.ofSeconds(6).toNanos();
            while(databaseNow(s.db.owner).isBefore(expiry)) {if(System.nanoTime()>bound)fail("database clock did not cross original expiry");Thread.sleep(20);}
            refused(s,new ResourceTaskAllocation(a.scope(),null,task,a.modelProfile()),"original lifetime is exhausted");});
    }
    @Test void childCannotLowerTheActualParentRiskTier() throws Exception {
        isolated((s,f,a)->{var p=parent(s,f,a);execute(s.db.owner,"update task.agent_task set contract=jsonb_set(contract,'{riskTier}','\"HIGH\"')");
            var t=a.contract();var child=new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),RiskTier.LOW,
                t.limits(),t.requestingSubject(),t.delegationGrantId());
            refused(s,new ResourceTaskAllocation(a.scope(),p.taskId(),child,a.modelProfile()),"widens actual parent task/grant");});
    }
    @Test void existingGrantProducerAndImmutableAncestryRejectCyclesWithoutBypassingTriggers() throws Exception {
        isolated((s,f,a)->{var store=new DelegationGrantStore(s.db.owner);var child=store.find(a.contract().delegationGrantId()).orElseThrow();
            var root=store.find(f.binding().issuingGrantId()).orElseThrow();
            var cycle=assertThrows(GrantException.class,()->grants(s).delegate(child.id(),child.agent(),root.agent(),
                Set.of("oa.agent.run"),databaseNow(s.db.owner).plusSeconds(300),null));
            assertEquals(GrantException.Code.CYCLE,cycle.code());
            var rewrite=assertThrows(SQLException.class,()->execute(s.db.owner,
                "update identity.delegation_grant set parent_grant_id='"+child.id()+"' where id='"+root.id()+"'"));
            assertEquals("42501",rewrite.getSQLState());assertEquals(root,store.find(root.id()).orElseThrow());
            assertEquals(child,store.find(child.id()).orElseThrow());insert(s,a);
            assertEquals(1,count(s.db.owner,"select count(*) from task.resource_task_allocation"));
        });
    }
}
