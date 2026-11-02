package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.invoke;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.EffectAuditDeliveryStoreTest.wrapping;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.ThreadId;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceTaskAllocations;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.JdbcNodeBudget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.JdbcToolCallBudget;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedToolGateway;
import io.github.bassemzohdy.openworkflow.agentic.runtime.agent.NodeRefused;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** Actual full V77 plus explicit proposed V78 in owned DBs. No profile approval/model reservation/dispatch proof. */
@QuarkusTest
class ResourceTaskAllocationStoreTest {
    @Inject TransactionManager manager;
    @Inject UserTransaction caller;
    @Inject Instance<ResourceTaskAllocations> defaults;
    ResourceTaskAllocationMigrationTest support;
    DataSource data;
    JdbcResourceTaskAllocations store;
    ResourceExecutionMigrationTest.Fixture f;
    ResourceTaskAllocation a;
    @BeforeEach void create() throws Exception {
        // Reuse the rehearsal's fixture helpers without inheriting or duplicating its @Test cases.
        support=new ResourceTaskAllocationMigrationTest();support.create();support.apply();data=support.db.owner;
        f=fixture(support.db,"a".repeat(64));a=support.candidate(f);store=new JdbcResourceTaskAllocations(data,manager);
    }
    @AfterEach void drop() throws Exception {if(support!=null)support.close();}
    long rows() throws SQLException {return count(data,"select count(*) from task.resource_task_allocation");}
    static ResourceTaskAllocation changed(ResourceTaskAllocation a) {
        var t=a.contract();var task=new AgentTaskContract(t.id(),"changed approval",t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),t.requestingSubject(),t.delegationGrantId());
        return new ResourceTaskAllocation(a.scope(),a.parentTaskId(),task,a.modelProfile());
    }
    String scalar(String sql) throws Exception {try(var c=data.getConnection()){return ResourceTaskAllocationMigrationTest.scalar(c,sql);}}
    DelegationGrants grants(Clock clock) {
        return new DelegationGrants(new DelegationGrantStore(data),new IdentityAudit(e->{},clock,UlidGenerator.shared()),clock,UlidGenerator.shared(),Duration.ofHours(2));
    }
    void corrupt(String sql) throws Exception {
        Transactions.inTransaction(data,c->{try(var s=c.createStatement()){s.execute("set local session_replication_role=replica");s.executeUpdate(sql);}return null;});
    }
    @Test void exactRepeatAndReopenRetainOneOriginalContractAndLifetimeWithoutDefaultInstallation() throws Exception {
        assertTrue(defaults.isUnsatisfied());var first=store.allocate(a);
        assertEquals(first,store.allocate(a));assertEquals(first,new JdbcResourceTaskAllocations(data,manager).find(a.scope()).orElseThrow());
        assertEquals(1,rows());assertEquals(a,first.allocation());assertEquals(0,first.createdAt().getNano()%1000);
        assertFalse(first.deadline().isAfter(first.createdAt().plus(a.contract().limits().maxWallClock())));
        assertEquals(0,count(data,"select count(*) from task.resource_execution"));
    }

    @Test void taskFenceChecksOriginalAllocationAndRefusesAfterTargetRevocation() throws Exception {
        var allocation=store.allocate(a);var entry=f.entry(1);var approved=new AtomicBoolean(true);
        var reads=new AtomicInteger();
        try(var taskFence=new ResourceTaskExecutionFence(data,manager,entry,allocation,(checked,task)->{
            assertEquals(entry,checked);assertEquals(allocation,task);reads.incrementAndGet();
            if(!approved.get())throw new IllegalStateException("fixture approval revoked");
        });var gate=new JdbcResourceExecutionGate(data,manager,1)) {
            assertDoesNotThrow(()->taskFence.requireCurrent(entry));
            assertThrows(IllegalStateException.class,()->taskFence.requireCurrent(f.entry(2)));
            assertThrows(Exception.class,()->new ObjectMapper().writeValueAsString(taskFence));
            var builder=CompositeExecutionFence.builder();var auditChecks=new AtomicInteger();
            for(var check:CompositeExecutionFence.Check.values()) {
                if(check==CompositeExecutionFence.Check.TASK_AND_TARGET)builder.with(check,taskFence);
                else if(check==CompositeExecutionFence.Check.AUDIT)builder.with(check,ignored->{
                    if(auditChecks.incrementAndGet()==2)approved.set(false);
                });
                else builder.with(check,ignored->{});
            }
            // Other checks and the approval source are fixtures; this is not production authorization.
            var paired=JdbcResourceExecutionGate.CredentialFence.pair(f.binding(),builder.build());
            var callbacks=new AtomicInteger();
            var attempt=gate.openGoverned(f.binding(),entry,UUID.randomUUID(),paired);
            assertThrows(IllegalStateException.class,()->attempt.executeGoverned(r->{callbacks.incrementAndGet();return "private";}));
            assertEquals(0,callbacks.get(),"target revocation during audit must prevent callback entry");
            assertTrue(gate.find(a.scope()).isPresent(),"the committed entry must be retained");
            assertThrows(IllegalStateException.class,()->gate.openGoverned(f.binding(),entry,UUID.randomUUID(),paired)
                    .executeGoverned(r->{callbacks.incrementAndGet();return "duplicate";}));
            assertTrue(reads.get()>=3);
            taskFence.close();
            assertThrows(IllegalStateException.class,()->taskFence.requireCurrent(entry));
        }
    }

    @Test void taskFenceRechecksDatabaseDeadlineAfterBlockingApproval() throws Exception {
        var original=a.contract();var profile=a.modelProfile();
        var shortContract=new AgentTaskContract(original.id(),original.goal(),original.permittedTools(),
                original.permittedDataScopes(),original.riskTier(),new ExecutionLimits(5,Duration.ofSeconds(8),5000),
                original.requestingSubject(),original.delegationGrantId());
        var shortProfile=new ApprovedModelProfile(profile.id(),profile.version(),profile.configurationFingerprint(),
                profile.agent(),profile.modelId(),profile.maxModelCalls(),profile.perCallCeilingMicros(),
                Duration.ofSeconds(1),profile.maxOutputTokens());
        var shortAllocation=new ResourceTaskAllocation(a.scope(),a.parentTaskId(),shortContract,shortProfile);
        var retained=store.allocate(shortAllocation);var entered=new AtomicBoolean();
        try(var fence=new ResourceTaskExecutionFence(data,manager,f.entry(1),retained,(entry,task)->{
            entered.set(true);
            long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            try {
                while(databaseNow(data).isBefore(task.deadline()) && System.nanoTime()<until)Thread.sleep(25);
                if(databaseNow(data).isBefore(task.deadline()))throw new IllegalStateException("test timed out");
            } catch(Exception e){throw new IllegalStateException(e);}
        })) {
            assertThrows(IllegalStateException.class,()->fence.requireCurrent(f.entry(1)));
            assertTrue(entered.get(),"the original allocation must have been live before approval blocked");
        }
    }
    @Test void aFreshAllocationAtomicallyCreatesTheBoundBudgetAndRetryDoesNotResetIt() throws Exception {
        var allocated=store.allocate(a);
        var budget=new JdbcNodeBudget(data).openExisting(a.scope().threadId().value(),a.modelProfile().agent(),a.contract());
        budget.admit();
        assertEquals(allocated,store.allocate(a));
        try(var c=data.getConnection();var q=c.prepareStatement(
                "select steps,started_at,deadline from task.agent_node_budget where task_id=?")) {
            q.setString(1,a.contract().id().value());
            try(var r=q.executeQuery()) {
                assertTrue(r.next()); assertEquals(1,r.getInt(1));
                assertEquals(allocated.createdAt(),r.getTimestamp(2).toInstant());
                assertEquals(allocated.deadline(),r.getTimestamp(3).toInstant());
                assertFalse(r.next());
            }
        }
        assertThrows(NodeRefused.class,()->new JdbcNodeBudget(data).openExisting(
                a.scope().threadId().value(),new Subject(SubjectKind.AGENT,"other-agent"),a.contract()));
    }

    @Test void aBudgetWriteFailureRollsBackTheNewAllocation() throws Exception {
        try(var c=data.getConnection();var s=c.createStatement()) {
            s.execute("create function task.reject_budget_test() returns trigger language plpgsql as $$ begin raise exception 'test failure'; end $$");
            s.execute("create trigger reject_budget_test before insert on task.agent_node_budget for each row execute function task.reject_budget_test()");
        }
        assertThrows(RuntimeException.class,()->store.allocate(a));
        assertEquals(0,rows());
        assertEquals(0,count(data,"select count(*) from task.agent_node_budget"));
    }

    @Test void freshToolBudgetCommitsWithAllocationAndRetryNeverResetsIt() throws Exception {
        var allocated = store.allocateWithToolBudget(a, 2);
        var budget = new JdbcToolCallBudget(data).openExisting(a.scope().threadId().value(), a.modelProfile().agent(), a.contract());
        var approval = new GovernedToolGateway.Approval(a.modelProfile().agent(), a.contract(), 1000);
        budget.admit(approval);
        assertEquals(allocated, store.allocateWithToolBudget(a, 2));
        assertEquals(1, rows());
        assertEquals(1, count(data, "select count(*) from task.agent_node_budget"));
        assertEquals(1, count(data, "select count(*) from task.agent_tool_budget"));
        assertThrows(IllegalStateException.class, () -> store.allocateWithToolBudget(a, 3));
        budget.admit(approval);
        assertThrows(NodeRefused.class, () -> budget.admit(approval));
    }

    private ApprovedToolProfile toolProfile(String version) {
        return new ApprovedToolProfile("tool-profile", version, "e".repeat(64), a.modelProfile().agent(),
                a.contract().permittedTools(), 2, Duration.ofSeconds(3));
    }

    @Test void freshToolProfileIsRetainedWithAllocationAndExactRetries() throws Exception {
        var profile = toolProfile("1");
        var first = store.allocateWithToolProfile(a, profile);
        assertEquals(first, store.allocateWithToolProfile(a, profile));
        assertThrows(IllegalStateException.class, () -> store.allocateWithToolProfile(a, toolProfile("2")));
        assertEquals(1, rows());
        assertEquals(1, count(data, "select count(*) from task.agent_tool_budget"));
        assertEquals(1, count(data, "select count(*) from task.agent_tool_profile"));
        try (var c=data.getConnection(); var q=c.prepareStatement("update task.agent_tool_profile set profile_version='forged' where task_id=?")) {
            q.setString(1,a.contract().id().value()); assertThrows(SQLException.class,q::executeUpdate);
        }
    }

    @Test void retainedProfileAndBudgetGovernTheToolGateway() throws Exception {
        var calls = new AtomicInteger();
        var entry = new GovernedToolGateway.Registration("{\"type\":\"object\"}", "{\"type\":\"object\"}",
                false, Duration.ofSeconds(2), "a".repeat(64), args -> { calls.incrementAndGet(); return "{}"; });
        var registry = Map.of("tool:research", entry);
        var profile = new ApprovedToolProfile("tool-profile", "1", GovernedToolGateway.configurationFingerprint(registry),
                a.modelProfile().agent(), Set.of("tool:research"), 1, Duration.ofSeconds(2));
        var allocated = store.allocateWithToolProfile(a, profile);
        var budget = new JdbcToolCallBudget(data).openProfiled(allocated, profile);
        var approved = new GovernedToolGateway.Approval(profile.agent(), a.contract(), 1000);
        var pep = new io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep(request ->
                new io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision("test",
                        io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome.ALLOW, "allow",
                        new io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef("test", "1"), Instant.now()), event -> { });
        var gateway = new GovernedToolGateway(registry, () -> Optional.of(approved), profile, pep, event -> { }, budget, Clock.systemUTC());
        var call = new io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall(a.contract().id(), "tool:research", "{}");
        assertInstanceOf(io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult.Completed.class, gateway.invoke(call));
        assertInstanceOf(io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult.Rejected.class, gateway.invoke(call));
        assertEquals(1, calls.get());
    }

    @Test void retainedProfileCorruptionRefusesBeforeTheToolAndDoesNotConsumeBudget() throws Exception {
        var calls = new AtomicInteger();
        var entry = new GovernedToolGateway.Registration("{\"type\":\"object\"}", "{\"type\":\"object\"}",
                false, Duration.ofSeconds(2), "a".repeat(64), args -> { calls.incrementAndGet(); return "{}"; });
        var registry = Map.of("tool:research", entry);
        var profile = new ApprovedToolProfile("tool-profile", "1", GovernedToolGateway.configurationFingerprint(registry),
                a.modelProfile().agent(), Set.of("tool:research"), 1, Duration.ofSeconds(2));
        var allocated = store.allocateWithToolProfile(a, profile);
        var budget = new JdbcToolCallBudget(data).openProfiled(allocated, profile);
        var approved = new GovernedToolGateway.Approval(profile.agent(), a.contract(), 1000);
        var pep = new io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep(request ->
                new io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision("test",
                        io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome.ALLOW, "allow",
                        new io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef("test", "1"), Instant.now()), event -> { });
        var gateway = new GovernedToolGateway(registry, () -> Optional.of(approved), profile, pep, event -> { }, budget, Clock.systemUTC());
        corrupt("update task.agent_tool_profile set profile_version='forged'");
        var call = new io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall(a.contract().id(), "tool:research", "{}");
        assertInstanceOf(io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult.Rejected.class, gateway.invoke(call));
        assertEquals(0, calls.get());
        assertEquals("0", scalar("select calls::text from task.agent_tool_budget"));
    }

    @Test void profileCorruptedDuringToolExecutionWithholdsTheOutput() throws Exception {
        var calls = new AtomicInteger();
        var entry = new GovernedToolGateway.Registration("{\"type\":\"object\"}", "{\"type\":\"object\"}",
                false, Duration.ofSeconds(3), "a".repeat(64), args -> {
                    calls.incrementAndGet(); corrupt("update task.agent_tool_profile set profile_version='forged'"); return "{}";
                });
        var registry = Map.of("tool:research", entry);
        var profile = new ApprovedToolProfile("tool-profile", "1", GovernedToolGateway.configurationFingerprint(registry),
                a.modelProfile().agent(), Set.of("tool:research"), 1, Duration.ofSeconds(3));
        var allocated = store.allocateWithToolProfile(a, profile);
        var budget = new JdbcToolCallBudget(data).openProfiled(allocated, profile);
        var approved = new GovernedToolGateway.Approval(profile.agent(), a.contract(), 1000);
        var pep = new io.github.bassemzohdy.openworkflow.agentic.runtime.policy.Pep(request ->
                new io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision("test",
                        io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome.ALLOW, "allow",
                        new io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef("test", "1"), Instant.now()), event -> { });
        var gateway = new GovernedToolGateway(registry, () -> Optional.of(approved), profile, pep, event -> { }, budget, Clock.systemUTC());
        var call = new io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolCall(a.contract().id(), "tool:research", "{}");
        var result = assertInstanceOf(io.github.bassemzohdy.openworkflow.agentic.contracts.port.ToolResult.Failed.class, gateway.invoke(call));
        assertFalse(result.effectMayHaveHappened()); assertEquals(1, calls.get());
    }

    @Test void aLegacyToolBudgetIsNotSilentlyGivenAProfile() throws Exception {
        store.allocateWithToolBudget(a, 2);
        assertThrows(IllegalStateException.class, () -> store.allocateWithToolProfile(a, toolProfile("1")));
        assertEquals(0, count(data, "select count(*) from task.agent_tool_profile"));
    }

    @Test void failureRetainingToolProfileRollsBackBothBudgetsAndAllocation() throws Exception {
        try (var c=data.getConnection(); var s=c.createStatement()) {
            s.execute("create function task.reject_tool_profile_test() returns trigger language plpgsql as $$ begin raise exception 'test failure'; end $$");
            s.execute("create trigger reject_tool_profile_test before insert on task.agent_tool_profile for each row execute function task.reject_tool_profile_test()");
        }
        assertThrows(RuntimeException.class, () -> store.allocateWithToolProfile(a, toolProfile("1")));
        assertEquals(0, rows());
        assertEquals(0, count(data, "select count(*) from task.agent_node_budget"));
        assertEquals(0, count(data, "select count(*) from task.agent_tool_budget"));
        assertEquals(0, count(data, "select count(*) from task.agent_tool_profile"));
    }

    @Test void profiledAllocationRejectsToolsOutsideTheApprovedTaskBeforeWriting() throws Exception {
        var profile = new ApprovedToolProfile("tool-profile", "1", "e".repeat(64), a.modelProfile().agent(),
                Set.of("tool:unapproved"), 2, Duration.ofSeconds(3));
        assertThrows(IllegalArgumentException.class, () -> store.allocateWithToolProfile(a, profile));
        assertEquals(0, rows());
    }

    @Test void failureCreatingToolBudgetRollsBackAllocationAndNodeBudget() throws Exception {
        try (var c=data.getConnection(); var s=c.createStatement()) {
            s.execute("create function task.reject_tool_budget_test() returns trigger language plpgsql as $$ begin raise exception 'test failure'; end $$");
            s.execute("create trigger reject_tool_budget_test before insert on task.agent_tool_budget for each row execute function task.reject_tool_budget_test()");
        }
        assertThrows(RuntimeException.class, () -> store.allocateWithToolBudget(a, 2));
        assertEquals(0, rows());
        assertEquals(0, count(data, "select count(*) from task.agent_node_budget"));
        assertEquals(0, count(data, "select count(*) from task.agent_tool_budget"));
    }

    @Test void anOlderAllocationIsNeverBackfilledWithAToolBudget() throws Exception {
        var original = store.allocate(a);
        assertThrows(IllegalStateException.class, () -> store.allocateWithToolBudget(a, 2));
        assertEquals(original, store.allocate(a));
        assertEquals(0, count(data, "select count(*) from task.agent_tool_budget"));
    }

    @Test void exactConcurrentToolAllocationRetriesRetainOneBudget() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                start.await(); return store.allocateWithToolBudget(a, 2);
            })).toList();
            start.countDown();
            var first = results.getFirst().get(15, TimeUnit.SECONDS);
            for (var result : results) assertEquals(first, result.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, rows());
        assertEquals(1, count(data, "select count(*) from task.agent_tool_budget"));
    }

    @Test void concurrentDifferentToolCeilingsCannotBothBeAccepted() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var two = pool.submit(() -> { start.await(); return store.allocateWithToolBudget(a, 2); });
            var three = pool.submit(() -> { start.await(); return store.allocateWithToolBudget(a, 3); });
            start.countDown();
            int successes = 0;
            for (var result : List.of(two, three)) {
                try { result.get(15, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException conflict) { assertInstanceOf(IllegalStateException.class, conflict.getCause()); }
            }
            assertEquals(1, successes);
        }
        assertEquals(1, rows());
        assertEquals(1, count(data, "select count(*) from task.agent_tool_budget"));
    }

    @Test void lostCommitReplyRecoversTheSameToolBudgetWithoutReset() throws Exception {
        var fail = new AtomicBoolean(true);
        var lost = wrapping(data, c -> (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class[]{Connection.class}, (p, m, args) -> {
                    Object result = invoke(m, c, args);
                    if (m.getName().equals("commit") && fail.getAndSet(false)) throw new SQLException("lost reply", "08006");
                    return result;
                }));
        assertThrows(StoreUnavailableException.class,
                () -> new JdbcResourceTaskAllocations(lost, manager).allocateWithToolBudget(a, 1));
        var budget = new JdbcToolCallBudget(data).openExisting(a.scope().threadId().value(), a.modelProfile().agent(), a.contract());
        var approval = new GovernedToolGateway.Approval(a.modelProfile().agent(), a.contract(), 1000);
        budget.admit(approval);
        assertEquals(a, store.allocateWithToolBudget(a, 1).allocation());
        assertThrows(NodeRefused.class, () -> budget.admit(approval));
    }

    @Test void anExistingAllocationWithoutABudgetIsNeverBackfilled() throws Exception {
        var first=store.allocate(a);
        corrupt("delete from task.agent_node_budget"); // Simulates a legacy allocation in this disposable fixture.
        assertEquals(first,store.allocate(a));
        assertEquals(0,count(data,"select count(*) from task.agent_node_budget"));
        assertThrows(NodeRefused.class,()->new JdbcNodeBudget(data).openExisting(
                a.scope().threadId().value(),a.modelProfile().agent(),a.contract()));
    }

    @Test void conflictsAndFullScopeMismatchAreNotAbsenceOrAnotherAllocation() throws Exception {
        store.allocate(a);assertThrows(IllegalStateException.class,()->store.allocate(changed(a)));assertEquals(1,rows());
        var s=a.scope();var wrong=new ResourceCallScope(s.tenant(),s.instanceId(),s.owner(),s.applicationId(),s.engineInstanceId(),s.namespace(),s.definition(),s.version(),s.taskPosition(),s.iteration(),s.logicalOccurrence(),s.audience(),s.operation(),"d".repeat(64),s.requestFingerprint());
        assertEquals(s.key(),wrong.key());assertThrows(IllegalStateException.class,()->store.find(wrong));
        var absent=fixture(support.db,"a".repeat(64));assertTrue(store.find(absent.binding().scope()).isEmpty());
    }
    @Test void concurrentExactRetriesHaveOneOriginalRecord() throws Exception {
        var begin=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);
        try {
            var results=new ArrayList<Future<AllocatedResourceTask>>();for(int i=0;i<6;i++)results.add(pool.submit(()->{begin.await();return store.allocate(a);}));
            begin.countDown();var first=results.getFirst().get(15,TimeUnit.SECONDS);for(var r:results)assertEquals(first,r.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,rows());
        assertEquals(1,count(data,"select count(*) from task.agent_node_budget"));
    }
    @Test void originalLifetimeRemainsHistoricalAfterRegistryCompletionAndGrantRevocation() throws Exception {
        var first=store.allocate(a);var registry=new JdbcInstanceStore(data);var row=f.row();
        registry.replace(row.successor(databaseNow(data),InstanceStatus.COMPLETED,row.engineInstanceId(),row.startAttempts(),null,null),row.version(),"test.complete",row.owner());
        grants(Clock.systemUTC()).revoke(a.contract().delegationGrantId(),a.scope().owner(),"test revoke",null);
        assertEquals(first,store.allocate(a));assertEquals(first,store.find(a.scope()).orElseThrow());assertEquals(1,rows());
        // Exact expired repeats are DATA and do not reauthorize, refresh deadline, reset budget or enter a graph.
    }
    @Test void anActuallyExpiredOriginalDeadlineIsNeverRenewedByExactRetry() throws Exception {
        var now=databaseNow(data);var root=new DelegationGrantStore(data).find(f.binding().issuingGrantId()).orElseThrow();
        var child=grants(Clock.fixed(now,ZoneOffset.UTC)).delegate(root.id(),root.agent(),a.modelProfile().agent(),Set.of("oa.agent.run"),now.plusSeconds(5),null);
        var t=a.contract();var contract=new AgentTaskContract(t.id(),t.goal(),t.permittedTools(),t.permittedDataScopes(),t.riskTier(),t.limits(),t.requestingSubject(),child.id());
        var candidate=new ResourceTaskAllocation(a.scope(),null,contract,a.modelProfile());var first=store.allocateWithToolBudget(candidate,2);
        long bound=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        try(var c=data.getConnection();var q=c.createStatement()) {
            while(true) {
                try(var r=q.executeQuery("select clock_timestamp()")){r.next();if(!r.getTimestamp(1).toInstant().isBefore(first.deadline()))break;}
                if(System.nanoTime()>bound)fail("database clock did not cross the original bounded deadline");Thread.sleep(20);
            }
        }
        assertEquals(first,store.allocate(candidate));assertEquals(first,store.find(candidate.scope()).orElseThrow());assertEquals(1,rows());
        assertEquals(first,store.allocateWithToolBudget(candidate,2),"historical retry does not extend deadline");
        assertThrows(NodeRefused.class, () -> new JdbcToolCallBudget(data).openExisting(
                candidate.scope().threadId().value(),candidate.modelProfile().agent(),candidate.contract()));
    }
    @Test void actualCommitThenLostReplyReopensTheSameStoredTaskWithoutRenewal() throws Exception {
        var fail=new AtomicBoolean(true);var lost=wrapping(data,c->(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
            Object result=invoke(m,c,args);if(m.getName().equals("commit")&&fail.getAndSet(false))throw new SQLException("actual allocation reply lost","08006");return result;
        }));
        assertThrows(StoreUnavailableException.class,()->new JdbcResourceTaskAllocations(lost,manager).allocate(a));
        var recovered=store.find(a.scope()).orElseThrow();assertEquals(recovered,store.allocate(a));assertEquals(1,rows());
        assertEquals(1,count(data,"select count(*) from task.agent_node_budget"));
        new JdbcNodeBudget(data).openExisting(a.scope().threadId().value(),a.modelProfile().agent(),a.contract()).admit();
    }
    @Test void actualCallerRollbackCannotEraseTheCommittedAllocation() throws Exception {
        caller.begin();AllocatedResourceTask first;
        try{first=store.allocate(a);assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertEquals(first,store.find(a.scope()).orElseThrow());assertEquals(1,rows());
    }
    @Test void unavailableStoreIsNotAnAbsentAllocation() {
        var unavailable=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(p,m,args)->{throw new SQLException("offline","08006");});
        var failed=new JdbcResourceTaskAllocations(unavailable,manager);
        assertThrows(StoreUnavailableException.class,()->failed.find(a.scope()));assertThrows(StoreUnavailableException.class,()->failed.allocate(a));
    }
    @Test void corruptStoredContractAndMissingOccurrenceRefuseRatherThanDropMetadata() throws Exception {
        store.allocate(a);corrupt("update task.resource_task_allocation set contract=contract||'{\"extra\":true}'::jsonb");
        assertThrows(IllegalStateException.class,()->store.find(a.scope()));
        corrupt("update task.resource_task_allocation set contract=contract-'extra'");
        assertEquals(a,store.find(a.scope()).orElseThrow().allocation());
        corrupt("delete from task.resource_occurrence");assertThrows(IllegalStateException.class,()->store.find(a.scope()));
    }
    @Test void databaseDataGuardStillRequiresCurrentRegistryForANewAllocation() throws Exception {
        var registry=new JdbcInstanceStore(data);var row=f.row();
        registry.replace(row.successor(databaseNow(data),InstanceStatus.CANCEL_REQUESTED,row.engineInstanceId(),row.startAttempts(),null,null),row.version(),"test.cancel",row.owner());
        assertThrows(StoreUnavailableException.class,()->store.allocate(a));assertEquals(0,rows());
    }
    @Test void aGrantRevokedWhileTheActualRegistryLockBlocksIsNotUsedByTheNewAllocation() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        try(var held=data.getConnection()) {
            held.setAutoCommit(false);try(var q=held.prepareStatement("select id from task.instance where id=? for update")){q.setString(1,a.scope().instanceId().value());q.executeQuery().close();}
            var pid=new AtomicInteger();var started=new CountDownLatch(1);
            var observed=wrapping(data,c->{pid.set(Integer.parseInt(ResourceTaskAllocationMigrationTest.scalar(c,"select pg_backend_pid()")));started.countDown();return c;});
            var pending=pool.submit(()->new JdbcResourceTaskAllocations(observed,manager).allocate(a));
            assertTrue(started.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
            while(count(data,"select count(*) from pg_locks where pid="+pid.get()+" and not granted")==0) {if(System.nanoTime()>end)fail("allocator did not block on registry lock");Thread.sleep(10);}
            grants(Clock.systemUTC()).revoke(a.contract().delegationGrantId(),a.scope().owner(),"blocked allocation revoke",null);held.commit();
            var error=assertThrows(ExecutionException.class,()->pending.get(10,TimeUnit.SECONDS));assertInstanceOf(StoreUnavailableException.class,error.getCause());assertEquals(0,rows());
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void actualParentTaskIsRetainedWithoutInventingTheCanonicalCallThread() throws Exception {
        var t=a.contract();var ids=UlidGenerator.shared();
        var parentContract=new AgentTaskContract(ids.next(),"parent",t.permittedTools(),t.permittedDataScopes(),RiskTier.MEDIUM,
                new ExecutionLimits(10,Duration.ofSeconds(600),10000),t.requestingSubject(),f.binding().issuingGrantId());
        var parent=new AgentTaskRecord(parentContract.id(),a.scope().tenant(),a.scope().owner(),a.scope().instanceId().value(),
                ThreadId.derive(a.scope().tenant(),a.scope().owner().id(),a.scope().instanceId().value()),parentContract,AgentTaskStatus.PENDING,0);
        var tasks=new JdbcTaskStore(data);tasks.insert(parent);
        var child=new ResourceTaskAllocation(a.scope(),parent.taskId(),t,a.modelProfile());var first=store.allocate(child);
        assertEquals(parent,tasks.find(parent.taskId()).orElseThrow());assertNotEquals(parent.threadId(),first.allocation().scope().threadId());
        assertEquals(first,store.find(a.scope()).orElseThrow());
    }
    @Test void inheritedSerializableAndTimeoutSettingsAreNotDowngraded() throws Exception {
        var seen=new ArrayList<String>();var configured=wrapping(data,c->{
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var s=c.createStatement()){s.execute("set lock_timeout='200ms';set statement_timeout='500ms'");}
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,args)->{
                if(m.getName().equals("commit"))seen.add(ResourceTaskAllocationMigrationTest.scalar(c,"select current_setting('transaction_isolation')||':'||current_setting('lock_timeout')||':'||current_setting('statement_timeout')"));
                return invoke(m,c,args);
            });
        });
        var bounded=new JdbcResourceTaskAllocations(configured,manager);var first=bounded.allocate(a);assertEquals(first,bounded.find(a.scope()).orElseThrow());
        assertFalse(seen.isEmpty());assertTrue(seen.stream().allMatch(x->x.equals("serializable:200ms:500ms")));
    }
    @Test void resumeFailurePreservesPrimaryOrRefusesReturnAfterCommittedData() throws Exception {
        var faulty=(TransactionManager)Proxy.newProxyInstance(TransactionManager.class.getClassLoader(),new Class[]{TransactionManager.class},(p,m,args)->{
            Object result=invoke(m,manager,args);if(m.getName().equals("resume"))throw new SystemException("resume diagnostic");return result;
        });
        var failed=new JdbcResourceTaskAllocations(data,faulty);store.allocate(a);
        caller.begin();try {
            var error=assertThrows(IllegalStateException.class,()->failed.allocate(changed(a)));assertEquals(1,error.getSuppressed().length);
            assertInstanceOf(SystemException.class,error.getSuppressed()[0]);assertEquals(Status.STATUS_ACTIVE,caller.getStatus());
        } finally {caller.rollback();}
        var other=fixture(support.db,"a".repeat(64));var candidate=support.candidate(other);
        caller.begin();try{assertThrows(StoreUnavailableException.class,()->failed.allocate(candidate));assertEquals(Status.STATUS_ACTIVE,caller.getStatus());}finally{caller.rollback();}
        assertTrue(store.find(candidate.scope()).isPresent());assertEquals(2,rows());
    }
}
