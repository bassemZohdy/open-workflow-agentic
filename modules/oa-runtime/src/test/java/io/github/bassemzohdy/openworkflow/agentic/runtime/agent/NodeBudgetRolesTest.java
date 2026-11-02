package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static io.github.bassemzohdy.openworkflow.agentic.runtime.agent.GovernedLlmClientTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/** Actual grant template on disposable PostgreSQL roles, not deployment datasource configuration. */
class NodeBudgetRolesTest {
    private static final String PASSWORD = "budget-role-fixture-only";

    @Test void executorCanConsumeButCannotCreateResetOrAssumeAllocator() throws Exception {
        var roles = new ArrayList<String>();
        var admin = admin();
        try (var db = AgentTestDatabase.privateDatabase(true)) {
            String allocator = role(admin, roles), executor = role(admin, roles), outsider = role(admin, roles);
            String grants = Files.readString(root().resolve("deploy/sql/agent-node-budget-grants.sql"))
                    .replace("__BUDGET_ALLOCATOR_ROLE__", allocator).replace("__BUDGET_EXECUTOR_ROLE__", executor);
            execute(db.dataSource(), grants);
            var allocate = as(db.dataSource(), allocator); var run = as(db.dataSource(), executor);
            var unprivileged = as(db.dataSource(), outsider);
            var task = task();
            new JdbcNodeBudget(db.dataSource()).registerNew("role-test-thread", AGENT, task);
            var budget = new JdbcNodeBudget(run).openExisting("role-test-thread", AGENT, task);
            budget.admit(); budget.requireCurrent();
            assertThrows(StoreUnavailableException.class, () -> new JdbcNodeBudget(run).registerNew("forged", AGENT, task()));
            denied(run, "update task.agent_node_budget set binding=binding");
            denied(run, "update task.agent_node_budget set deadline=deadline+interval '1 hour'");
            denied(run, "delete from task.agent_node_budget");
            denied(run, "truncate task.agent_node_budget");
            denied(run, "create table task.budget_role_forbidden(id integer)");
            denied(run, "set role " + allocator);
            assertEquals("23514", sqlFailure(run,"update task.agent_node_budget set steps=0").getSQLState());
            denied(allocate, "update task.agent_node_budget set steps=steps+1");
            denied(allocate, "delete from task.agent_node_budget");
            denied(allocate, "set role " + executor);
            denied(unprivileged, "select * from task.agent_node_budget");
            denied(unprivileged, "set role " + allocator);
            try (var c=allocate.getConnection(); var q=c.prepareStatement("""
                    insert into task.agent_node_budget(task_id,binding,max_steps,started_at,deadline)
                    values (?, ?, 1, clock_timestamp(), clock_timestamp()+interval '1 minute')
                    """)) {
                q.setString(1,UlidGenerator.shared().next().value()); q.setString(2,"a".repeat(64));
                assertEquals(1,q.executeUpdate());
            }
            budget.admit();
            assertThrows(NodeRefused.class,budget::admit);
        } finally {
            for (String role : roles) execute(admin,"drop role " + role);
        }
    }

    private static AgentTaskContract task() {
        return new AgentTaskContract(UlidGenerator.shared().next(), TASK.goal(), TASK.permittedTools(),
                TASK.permittedDataScopes(), TASK.riskTier(), new ExecutionLimits(2,Duration.ofMinutes(5),1000),
                TASK.requestingSubject(), TASK.delegationGrantId());
    }
    private static String role(DataSource admin,List<String> roles) throws SQLException {
        String name="oa_budget_"+UUID.randomUUID().toString().replace("-","");
        execute(admin,"create role " + name + " login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password '" + PASSWORD + "'");
        roles.add(name); return name;
    }
    private static PGSimpleDataSource admin() {
        var ds=new PGSimpleDataSource(); ds.setURL(AgentTestDatabase.URL); ds.setDatabaseName("postgres");
        ds.setUser(AgentTestDatabase.USER); ds.setPassword(AgentTestDatabase.PASSWORD); return ds;
    }
    private static PGSimpleDataSource as(PGSimpleDataSource base,String role) {
        var ds=new PGSimpleDataSource(); ds.setURL(AgentTestDatabase.URL); ds.setDatabaseName(base.getDatabaseName());
        ds.setUser(role); ds.setPassword(PASSWORD); return ds;
    }
    private static void execute(DataSource ds,String sql) throws SQLException {
        try(var c=ds.getConnection();var s=c.createStatement()){s.execute(sql);}
    }
    private static SQLException sqlFailure(DataSource ds,String sql) {
        return assertThrows(SQLException.class,()->execute(ds,sql));
    }
    private static void denied(DataSource ds,String sql) {
        assertEquals("42501",sqlFailure(ds,sql).getSQLState(),sql);
    }
    private static Path root() {
        for(Path p=Path.of("").toAbsolutePath();p!=null;p=p.getParent())
            if(Files.isRegularFile(p.resolve("deploy/sql/agent-node-budget-grants.sql"))) return p;
        throw new IllegalStateException("budget grant template missing");
    }
}
