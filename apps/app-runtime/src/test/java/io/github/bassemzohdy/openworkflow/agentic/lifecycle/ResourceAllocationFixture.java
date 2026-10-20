package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.TaskStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Shared owned PostgreSQL metadata fixtures. Concrete tests own their generated schemas and close only their rows. */
abstract class ResourceAllocationFixture {
    @Inject DataSource data;
    @Inject InstanceStore registry;
    @Inject TaskStore tasks;
    @Inject DelegationGrants grants;
    @Inject JdbcResourceCallBindings bindings;
    @Inject TransactionManager transactions;
    @Inject UserTransaction callerTransaction;
    static final Subject ALICE=new Subject(SubjectKind.USER,"alice"), FLOW=new Subject(SubjectKind.WORKFLOW,"allocation-workflow"),
            AGENT=new Subject(SubjectKind.AGENT,"allocation-research");
    final List<InstanceRecord> rows=new ArrayList<>();
    final List<AgentTaskRecord> parents=new ArrayList<>();
    record Fixture(InstanceRecord row,ResourceCallBinding binding,DelegationGrant root,DelegationGrant model,
                   AgentTaskRecord parent,ResourceTaskAllocation allocation) { }
    static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    Fixture fixture(boolean withParent) { return fixture(withParent, java.util.function.UnaryOperator.identity()); }
    Fixture fixture(boolean withParent, java.util.function.UnaryOperator<ApprovedModelProfile> customize) {
        var request=new StartRequest("allocation-"+UUID.randomUUID(),ALICE,new DefinitionRef("agentic/allocation-store-test","1"),Map.of(),"start");
        var row=registry.insertIfAbsent(InstanceRecord.starting(UlidGenerator.shared().next(),request,now()),"test.start").record();rows.add(row);
        row=registry.replace(row.successor(now(),InstanceStatus.RUNNING,"engine-"+row.id(),1,null,null),row.version(),"test.running",ALICE);
        var root=grants.grant(row.tenant(),ALICE,ALICE,FLOW,Set.of("oa.agent.run","oa.model.call"),now().plusSeconds(3600),null);
        var model=grants.delegate(root.id(),FLOW,AGENT,Set.of("oa.model.call"),now().plusSeconds(1800),null);
        var scope=new ResourceCallScope(row.tenant(),row.id(),ALICE,"app-𝄞",row.engineInstanceId(),"agentic","allocation-store-test","1",
                "/do/0/研究",1,"root/foreach/é","research","run","a".repeat(64),"b".repeat(64));
        String issuer="https://allocation-idp.test",jti=UUID.randomUUID().toString();Instant expires=now().plusSeconds(120);
        new JdbcIssuedTokenBindings(data).bind(issuer,jti,new IssuedTokenBindings.Binding(root.id(),row.tenant(),expires));
        var binding=new ResourceCallBinding(issuer,jti,root.id(),scope,expires.minusSeconds(120),expires);bindings.bind(binding);
        AgentTaskRecord parent=null;
        if(withParent) {
            var parentContract=new AgentTaskContract(UlidGenerator.shared().next(),"approved parent",Set.of("tool:research"),
                    Set.of("data:public"),RiskTier.MEDIUM,new ExecutionLimits(10,Duration.ofSeconds(600),10000),ALICE,root.id());
            parent=new AgentTaskRecord(parentContract.id(),row.tenant(),ALICE,row.id().value(),
                    ThreadId.derive(row.tenant(),ALICE.id(),row.id().value()),parentContract,AgentTaskStatus.PENDING,0);
            tasks.insert(parent);parents.add(parent);
        }
        var contract=new AgentTaskContract(UlidGenerator.shared().next(),"resource-request-sha256:"+scope.requestFingerprint()+" / 研究𝄞",
                Set.of("tool:research"),Set.of("data:public"),RiskTier.HIGH,
                new ExecutionLimits(5,Duration.ofSeconds(120),5000),ALICE,model.id());
        var profile=new ApprovedModelProfile("research-𝄞","1","c".repeat(64),AGENT,"approved-model",3,1000,Duration.ofSeconds(10,123),2048);
        return new Fixture(row,binding,root,model,parent,new ResourceTaskAllocation(scope,parent==null?null:parent.taskId(),contract,customize.apply(profile)));
    }
    @AfterEach void finishOnlyOwnedFixtures() {
        for(var original:parents) {
            var p=tasks.find(original.taskId()).orElseThrow();
            if(!Set.of(AgentTaskStatus.COMPLETED,AgentTaskStatus.FAILED,AgentTaskStatus.CANCELLED).contains(p.status()))
                tasks.replace(new AgentTaskRecord(p.taskId(),p.tenant(),p.owner(),p.workflowInstance(),p.threadId(),p.contract(),
                        AgentTaskStatus.CANCELLED,p.version()+1),p.version());
        }
        for(var original:rows) {
            var r=registry.find(original.tenant(),original.id()).orElseThrow();
            if(!r.status().isTerminal())registry.replace(r.successor(now(),InstanceStatus.COMPLETED,r.engineInstanceId(),r.startAttempts(),null,null),
                    r.version(),"test.complete",ALICE);
        }
    }
    static String resource(String name) throws Exception {
        try(var in=ResourceAllocationFixture.class.getClassLoader().getResourceAsStream("db/"+name)) {
            assertNotNull(in);return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    final class Arena implements AutoCloseable {
        final String schema="gpt_ra_"+UUID.randomUUID().toString().replace("-","");
        final DataSource redirected;
        final JdbcResourceTaskAllocations store;
        final AtomicBoolean loseCommit=new AtomicBoolean();
        Arena(Fixture fixture) throws Exception {this(fixture,true);}
        Arena(Fixture f,boolean allocationSchema) throws Exception {
            assertTrue(schema.matches("gpt_ra_[0-9a-f]{32}"));
            redirected=redirect(data,schema,loseCommit);store=new JdbcResourceTaskAllocations(redirected,transactions);
            boolean created=false;
            try(var c=data.getConnection()) {
                sql(c,"create schema "+schema);created=true;
                for(String table:List.of("instance","agent_task")) {
                    sql(c,"create table "+schema+"."+table+" as select * from task."+table+" where false");
                    sql(c,"alter table "+schema+"."+table+" add primary key(id)");
                }
                sql(c,"alter table "+schema+".instance add unique(tenant,id)");
                copy(c,"instance","id",f.row.id().value());
                if(f.parent!=null)copy(c,"agent_task","id",f.parent.taskId().value());
                apply(c,"migration/V73__resource_call_bindings.sql");
                apply(c,"migration/V74__canonical_resource_occurrence.sql");
                copy(c,"resource_call","call_key",f.binding.scope().key());
                copy(c,"resource_call_binding","call_key",f.binding.scope().key());
                apply(c,"proposed/V76__resource_execution_facts.sql");
                if(allocationSchema)apply(c,"proposed/V78__resource_task_allocation.sql");
            } catch(Exception failed) {if(created)close();throw failed;}
        }
        void add(Fixture f) throws Exception {
            try(var c=data.getConnection()) {
                for(String table:List.of("instance","agent_task","resource_call","resource_call_binding")) {
                    String column=table.equals("instance")||table.equals("agent_task")?"id":"call_key";
                    String value=table.equals("instance")?f.row.id().value():table.equals("agent_task")?
                            (f.parent==null?null:f.parent.taskId().value()):f.binding.scope().key();
                    if(value==null)continue;
                    try(var q=c.prepareStatement("insert into "+schema+"."+table+" select * from task."+table+" where "+column+"=? on conflict do nothing")) {
                        q.setString(1,value);q.executeUpdate();
                    }
                }
            }
        }
        void apply(Connection c,String path) throws Exception {sql(c,rewrite(resource(path),schema));}
        void copy(Connection c,String table,String column,String value) throws SQLException {
            try(var q=c.prepareStatement("insert into "+schema+"."+table+" select * from task."+table+" where "+column+"=?")) {
                q.setString(1,value);q.executeUpdate();
            }
        }
        void change(String statement) throws SQLException {try(var c=redirected.getConnection()){sql(c,statement);}}
        long count() throws SQLException {
            try(var c=redirected.getConnection();var q=c.createStatement();var r=q.executeQuery("select count(*) from "+schema+".resource_task_allocation")) {
                r.next();return r.getLong(1);
            }
        }
        @Override public void close() throws SQLException {
            // Only this freshly generated test-owned UUID schema; no shared table/foreign schema is deleted.
            assertTrue(schema.matches("gpt_ra_[0-9a-f]{32}"));
            try(var c=data.getConnection()){sql(c,"drop schema if exists "+schema+" cascade");}
        }
    }
    static String rewrite(String sql,String schema) {return sql.replaceAll("(?<![A-Za-z0-9_.])task\\.",schema+".");}
    static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException failed){throw failed.getCause();}
    }
    static DataSource redirect(DataSource data,String schema,AtomicBoolean loseCommit) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{
            Object result=invoke(data,m,a);
            if(!(result instanceof Connection connection))return result;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->{
                Object[] args=ca==null?null:ca.clone();
                if(args!=null && args.length>0 && args[0] instanceof String text)args[0]=rewrite(text,schema);
                Object value=invoke(connection,cm,args);
                if(cm.getName().equals("commit") && loseCommit.compareAndSet(true,false))throw new SQLException("test: actual commit reply lost","08006");
                return value;
            });
        });
    }
    static void sql(Connection c,String text) throws SQLException {try(var s=c.createStatement()){s.execute(text);}}
    static AgentTaskContract task(AgentTaskContract old,String goal,Set<String> tools,ExecutionLimits limits,Ulid grant) {
        return new AgentTaskContract(old.id(),goal,tools,old.permittedDataScopes(),old.riskTier(),limits,old.requestingSubject(),grant);
    }
    static ResourceTaskAllocation withTask(ResourceTaskAllocation a,AgentTaskContract task) {
        return new ResourceTaskAllocation(a.scope(),a.parentTaskId(),task,a.modelProfile());
    }
    static StoreUnavailableException refused(String state,Runnable work) {
        var failure=assertThrows(StoreUnavailableException.class,work::run);
        Throwable cause=failure;
        while(cause!=null && !(cause instanceof SQLException))cause=cause.getCause();
        assertNotNull(cause,"expected actual JDBC refusal, not a different failure");
        assertEquals(state,((SQLException)cause).getSQLState());
        return failure;
    }
}
