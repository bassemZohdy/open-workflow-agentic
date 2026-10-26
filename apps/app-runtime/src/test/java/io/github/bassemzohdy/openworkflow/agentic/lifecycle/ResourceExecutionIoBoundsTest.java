package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate.*;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException;
import java.lang.reflect.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL operation limits and owned-connection cleanup. No graph/current-authority claim. */
class ResourceExecutionIoBoundsTest {
    private static IoBounds bounds(long lock,long statement,long network) {
        return new IoBounds(Duration.ofMillis(lock),Duration.ofMillis(statement),Duration.ofMillis(network));
    }
    private static Attempt open(JdbcResourceExecutionGate gate,Fixture f) {
        return gate.openGoverned(f.binding(),f.entry(1),UUID.randomUUID(),CredentialFence.pair(f.binding(),entry->{ }));
    }
    @Test void aRealRegistryLockTimeoutRollsBackWithoutCallbackOrInventingAnEntry() throws Exception {
        try(var db=new Database();var gate=new JdbcResourceExecutionGate(db.owner,1,bounds(100,1000,3000))) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var callbacks=new AtomicInteger();
            try(var lock=db.owner.getConnection()) {
                lock.setAutoCommit(false);
                try(var q=lock.prepareStatement("select id from task.instance where id=? for update")) {
                    q.setString(1,f.row().id().value());try(var r=q.executeQuery()){assertTrue(r.next());}
                }
                try {
                    var failure=assertThrows(StoreUnavailableException.class,()->open(gate,f).executeGoverned(r->callbacks.incrementAndGet()));
                    assertEquals("55P03",sqlState(failure));assertEquals(0,callbacks.get());assertEquals(0,gate.activeCount());
                } finally {lock.rollback();}
            }
            assertTrue(gate.find(f.binding().scope()).isEmpty());
            assertEquals(Kind.CALLED,open(gate,f).executeGoverned(r->callbacks.incrementAndGet()).kind());
            assertEquals(1,callbacks.get());
        }
    }
    @Test void aRealStatementTimeoutCancelsExpensiveValidationBeforeAnyAcknowledgedEntry() throws Exception {
        try(var db=new Database();var bounded=new JdbcResourceExecutionGate(db.owner,1,bounds(50,100,2000))) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var callbacks=new AtomicInteger();
            execute(db.owner,"alter function task.resource_call_data_contract(task.resource_call) rename to resource_call_data_contract_owned_original");
            execute(db.owner,"""
                    create function task.resource_call_data_contract(invocation task.resource_call) returns text
                    language plpgsql immutable strict set search_path=pg_catalog as $$ begin
                    perform pg_catalog.pg_sleep(1);
                    return task.resource_call_data_contract_owned_original(invocation); end $$
                    """);
            var failure=assertThrows(StoreUnavailableException.class,()->open(bounded,f).executeGoverned(r->callbacks.incrementAndGet()));
            assertEquals("57014",sqlState(failure));assertEquals(0,callbacks.get());
            assertTrue(bounded.find(f.binding().scope()).isEmpty());
            try(var normal=new JdbcResourceExecutionGate(db.owner,1)) {
                assertEquals(Kind.CALLED,open(normal,f).executeGoverned(r->callbacks.incrementAndGet()).kind());
                assertEquals(1,callbacks.get());
            }
        }
    }
    @Test void stricterSessionLimitsAreNotWidenedAndStateIsRestoredBeforeCallbackAndLogicalClose() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));
            try(var physical=db.owner.getConnection()) {
                physical.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                physical.setNetworkTimeout(Runnable::run,20000);
                try(var q=physical.createStatement()){q.execute("set lock_timeout='3s'; set statement_timeout='10s'; set idle_in_transaction_session_timeout='20s'");}
                var before=state(physical);var during=new AtomicReference<State>();var closes=new AtomicInteger();
                DataSource pooledView=proxy(DataSource.class,(method,args)->{
                    if(!method.getName().equals("getConnection"))return invoke(db.owner,method,args);
                    return proxy(Connection.class,(cm,ca)->{
                        if(cm.getName().equals("close")){closes.incrementAndGet();assertEquals(before,state(physical));return null;}
                        Object value=invoke(physical,cm,ca);
                        if(cm.getName().equals("prepareStatement")&&ca[0].toString().contains("insert into task.resource_execution"))
                            return proxy(PreparedStatement.class,(sm,sa)->{
                                if(sm.getName().equals("executeUpdate"))during.set(state(physical));
                                return invoke(value,sm,sa);
                            });
                        return value;
                    });
                });
                try(var gate=new JdbcResourceExecutionGate(pooledView,1)) {
                    var result=open(gate,f).executeGoverned(r->{assertEquals(1,closes.get());assertEquals(before,assertDoesNotThrow(()->state(physical)));return "fixture";});
                    assertEquals(Kind.CALLED,result.kind());
                    assertEquals(new State(false,Connection.TRANSACTION_READ_COMMITTED,20000,List.of(3000L,10000L,20000L)),during.get());
                    assertEquals(before,state(physical));assertEquals(1,closes.get());
                }
            }
        }
    }
    @Test void resetFailureAfterActualCommitAbortsTheConnectionAndNeverCallsBack() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var callbacks=new AtomicInteger();var committed=new AtomicBoolean();var aborted=new AtomicBoolean();
            try(var physical=db.owner.getConnection()) {
                DataSource source=proxy(DataSource.class,(method,args)->{
                    if(!method.getName().equals("getConnection"))return invoke(db.owner,method,args);
                    return proxy(Connection.class,(cm,ca)->{
                        if(cm.getName().equals("setNetworkTimeout")&&committed.get()&&(Integer)ca[1]==0)throw new SQLException("owned reset failure");
                        if(cm.getName().equals("abort"))aborted.set(true);
                        Object result=invoke(physical,cm,ca);if(cm.getName().equals("commit"))committed.set(true);return result;
                    });
                });
                try(var gate=new JdbcResourceExecutionGate(source,1);var normal=new JdbcResourceExecutionGate(db.owner,1)) {
                    assertThrows(CommitUncertainException.class,()->open(gate,f).executeGoverned(r->callbacks.incrementAndGet()));
                    assertTrue(committed.get());assertTrue(aborted.get());assertTrue(physical.isClosed());
                    assertEquals(0,callbacks.get());assertEquals(0,gate.activeCount());assertTrue(normal.find(f.binding().scope()).isPresent());
                    assertEquals(Kind.RETAINED,open(normal,f).executeGoverned(r->callbacks.incrementAndGet()).kind());
                    assertEquals(0,callbacks.get());
                }
            }
        }
    }
    @Test void failedRollbackNeverSwitchesPendingWritesToAutoCommitAndAbortsInstead() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var inserted=new AtomicBoolean();var aborted=new AtomicBoolean();var callbacks=new AtomicInteger();
            try(var physical=db.owner.getConnection()) {
                DataSource source=proxy(DataSource.class,(method,args)->{
                    if(!method.getName().equals("getConnection"))return invoke(db.owner,method,args);
                    return proxy(Connection.class,(cm,ca)->{
                        if(cm.getName().equals("rollback"))throw new SQLException("owned rollback acknowledgement failure");
                        if(cm.getName().equals("setAutoCommit")&&(Boolean)ca[0])fail("auto-commit could persist the unacknowledged write");
                        if(cm.getName().equals("abort"))aborted.set(true);
                        Object result=invoke(physical,cm,ca);
                        if(cm.getName().equals("prepareStatement")) {
                            String sql=ca[0].toString();
                            return proxy(PreparedStatement.class,(sm,sa)->{
                                if(inserted.get()&&sql.contains("from task.resource_execution e")&&sm.getName().equals("executeQuery"))
                                    throw new SQLException("owned post-insert read failure");
                                Object answer=invoke(result,sm,sa);
                                if(sql.contains("insert into task.resource_execution")&&sm.getName().equals("executeUpdate"))inserted.set(true);
                                return answer;
                            });
                        }
                        return result;
                    });
                });
                try(var gate=new JdbcResourceExecutionGate(source,1);var normal=new JdbcResourceExecutionGate(db.owner,1)) {
                    assertThrows(CommitUncertainException.class,()->open(gate,f).executeGoverned(r->callbacks.incrementAndGet()));
                    assertTrue(inserted.get());assertTrue(aborted.get());assertTrue(physical.isClosed());assertEquals(0,callbacks.get());
                    assertTrue(normal.find(f.binding().scope()).isEmpty());assertEquals(0,gate.activeCount());
                }
            }
        }
    }
    @Test void unsupportedNetworkBoundsRefuseInsteadOfSilentlyUsingUnboundedIo() throws Exception {
        try(var db=new Database()) {
            db.migrate(null);var f=fixture(db,"a".repeat(64));var callbacks=new AtomicInteger();
            DataSource source=proxy(DataSource.class,(method,args)->{
                Object value=invoke(db.owner,method,args);
                if(method.getName().equals("getConnection"))return proxy(Connection.class,(cm,ca)->{
                    if(cm.getName().equals("setNetworkTimeout"))throw new SQLFeatureNotSupportedException("owned unsupported bound");
                    return invoke(value,cm,ca);
                });
                return value;
            });
            try(var gate=new JdbcResourceExecutionGate(source,1);var normal=new JdbcResourceExecutionGate(db.owner,1)) {
                assertThrows(StoreUnavailableException.class,()->open(gate,f).executeGoverned(r->callbacks.incrementAndGet()));
                assertEquals(0,callbacks.get());assertTrue(normal.find(f.binding().scope()).isEmpty());assertEquals(0,gate.activeCount());
            }
        }
    }
    @Test void invalidOrAmbiguousBoundsHaveNoUnboundedFallback() {
        assertThrows(IllegalArgumentException.class,()->bounds(0,100,1000));
        assertThrows(IllegalArgumentException.class,()->bounds(100,50,1000));
        assertThrows(IllegalArgumentException.class,()->bounds(10,100,100));
        assertThrows(IllegalArgumentException.class,()->bounds(10,100,300001));
        assertThrows(IllegalArgumentException.class,()->new IoBounds(Duration.ofNanos(1),Duration.ofSeconds(1),Duration.ofSeconds(2)));
    }
    private record State(boolean auto,int isolation,int network,List<Long> limits) { }
    private static State state(Connection c) throws SQLException {
        var limits=new ArrayList<Long>();
        try(var q=c.createStatement();var r=q.executeQuery("""
                select extract(epoch from pg_catalog.current_setting('lock_timeout')::pg_catalog.interval)*1000,
                       extract(epoch from pg_catalog.current_setting('statement_timeout')::pg_catalog.interval)*1000,
                       extract(epoch from pg_catalog.current_setting('idle_in_transaction_session_timeout')::pg_catalog.interval)*1000
                """)) {assertTrue(r.next());for(int i=1;i<=3;i++)limits.add(r.getLong(i));}
        return new State(c.getAutoCommit(),c.getTransactionIsolation(),c.getNetworkTimeout(),limits);
    }
    private static String sqlState(Throwable t) {
        for(int i=0;t!=null&&i<16;i++,t=t.getCause())if(t instanceof SQLException s)return s.getSQLState();return null;
    }
    @FunctionalInterface private interface Call {Object invoke(Method method,Object[] args) throws Throwable;}
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.invoke(m,a));
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
    }
}
