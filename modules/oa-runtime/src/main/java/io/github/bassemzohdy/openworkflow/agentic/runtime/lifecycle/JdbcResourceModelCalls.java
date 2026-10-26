package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.*;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Explicit opt-in V79 callback-once budget gate; NO CDI/default producer/provider activation. Only a known fresh
 * committed reservation may call the owned authorization/preparation and synchronous closure. Historical readback
 * never permits reentry. Full ceilings remain held on ALL uncertain/failed outcomes; no measured usage/refund is
 * invented. Database/identity checks surround blocking authorization and provider work; connections are closed and
 * caller JTA suspended across callbacks. Actual signed identity/PDP/audit/price/transport enforcement belongs to
 * the private consumer; a supplied guard or callback is never accepted from a request/serialized checkpoint.
 */
public final class JdbcResourceModelCalls implements ResourceModelCalls {
    private final DataSource data;
    private final TransactionManager transactions;
    public JdbcResourceModelCalls(DataSource data, TransactionManager transactions) {
        this.data=Objects.requireNonNull(data);this.transactions=Objects.requireNonNull(transactions);
    }
    @Override public <T> T invokeOnce(ResourceModelCall call, ResourceExecutionFence current, Authorization<T> authorization) {
        Objects.requireNonNull(call);Objects.requireNonNull(current);Objects.requireNonNull(authorization);
        return suspended(() -> {
            authority(call,current);
            final ModelCallReservation reservation;
            try { reservation=transaction(false,c->reserve(c,call)); }
            catch(StoreUnavailableException failed) {
                if(sqlState(failed,"OA001"))throw new ModelBudgetExhaustedException(failed);
                throw failed;
            }
            requireCurrent(reservation,current);
            Supplier<T> provider=Objects.requireNonNull(authorization.authorize(reservation),"authorized provider closure");
            requireCurrent(reservation,current); // AFTER blocking PDP/audit; BEFORE exactly one closure invocation.
            T answer=Objects.requireNonNull(provider.get(),"model callback result");
            requireCurrent(reservation,current); // AFTER provider work; BEFORE private output release.
            return answer;
        });
    }
    @Override public Optional<ModelCallReservation> find(ResourceModelCall call) {
        Objects.requireNonNull(call);
        return suspended(()->transaction(true,c->read(c,call)));
    }
    private ModelCallReservation reserve(Connection c,ResourceModelCall call) throws SQLException {
        var existing=read(c,call);
        if(existing.isPresent())throw new ModelCallAlreadyReservedException();
        try(var lock=c.prepareStatement("select id from task.instance where id=? for update")) {
            lock.setString(1,call.entry().scope().instanceId().value());
            try(var r=lock.executeQuery()){if(!r.next())throw conflict();}
        }
        // READ COMMITTED after the serialization wait, not the pre-lock absence snapshot.
        existing=read(c,call);if(existing.isPresent())throw new ModelCallAlreadyReservedException();
        var task=JdbcResourceTaskAllocations.read(c,call.entry().scope()).orElseThrow(JdbcResourceModelCalls::conflict);
        var allocation=task.allocation();
        if(call.maxOutputTokens()>allocation.modelProfile().maxOutputTokens())throw conflict();
        String account=ModelBudgetAccount.keyFor(allocation);
        if(account(c,account).isEmpty()) {
            try(var insert=c.prepareStatement("""
                    insert into task.model_budget_account(account_key,tenant,instance_id,owner_kind,owner_id,anchor_kind,anchor_id)
                    values(?,?,?,?,?,?,?) on conflict(account_key) do nothing
                    """)) {
                var scope=allocation.scope();insert.setString(1,account);insert.setString(2,scope.tenant());
                insert.setString(3,scope.instanceId().value());insert.setString(4,scope.owner().kind().name());insert.setString(5,scope.owner().id());
                insert.setString(6,allocation.parentTaskId()==null?"ALLOCATION":"TASK");
                insert.setString(7,allocation.parentTaskId()==null?allocation.key():allocation.parentTaskId().value());insert.executeUpdate();
            }
        }
        try(var insert=c.prepareStatement("""
                insert into task.model_call_reservation(reservation_key,call_key,entry_key,executor,node_occurrence,
                    request_fingerprint,max_output_tokens,intent_fingerprint,allocation_key,allocation_fingerprint,account_key)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setString(1,call.key());insert.setString(2,call.entry().callKey());insert.setString(3,call.entry().fingerprint());
            insert.setObject(4,call.executor());insert.setString(5,call.nodeOccurrence());insert.setString(6,call.requestFingerprint());
            insert.setInt(7,call.maxOutputTokens());insert.setString(8,call.fingerprint());insert.setString(9,allocation.key());
            insert.setString(10,allocation.fingerprint());insert.setString(11,account);insert.executeUpdate();
        }
        return read(c,call).orElseThrow(JdbcResourceModelCalls::conflict);
    }
    private void requireCurrent(ModelCallReservation expected,ResourceExecutionFence current) {
        authority(expected.call(),current);
        transaction(false,c->{
            Instant clock;
            try(var s=c.prepareStatement("select task.model_call_current_v1(?,?,?,?,?)")) {
                s.setString(1,expected.call().entry().callKey());s.setString(2,expected.task().allocation().fingerprint());
                s.setString(3,expected.call().entry().fingerprint());s.setObject(4,expected.call().executor());s.setString(5,expected.account().key());
                try(var r=s.executeQuery()){if(!r.next())throw conflict();clock=r.getTimestamp(1).toInstant();}
            }
            var actual=read(c,expected.call()).orElseThrow(JdbcResourceModelCalls::conflict);
            if(!actual.equals(expected) || !clock.isBefore(expected.callDeadline()))throw conflict();
            return null;
        });
        authority(expected.call(),current); // Signed identity/policy freshness AFTER all database I/O.
    }
    private static Optional<ModelBudgetAccount> account(Connection c,String key) throws SQLException {
        try(var s=c.prepareStatement("select * from task.model_budget_account where account_key=?")) {
            s.setString(1,key);
            try(var r=s.executeQuery()) {
                if(!r.next())return Optional.empty();
                var result=new ModelBudgetAccount(r.getString("account_key"),r.getString("tenant"),Ulid.parse(r.getString("instance_id")),
                        new Subject(SubjectKind.valueOf(r.getString("owner_kind")),r.getString("owner_id")),
                        ModelBudgetAccount.AnchorKind.valueOf(r.getString("anchor_kind")),r.getString("anchor_id"),
                        r.getString("source_contract_fingerprint"),r.getLong("max_cost_micros"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("deadline").toInstant());
                if(!result.fingerprint().equals(r.getString("fingerprint")) || r.next())throw conflict();
                return Optional.of(result);
            }
        }
    }
    private static Optional<ModelCallReservation> read(Connection c,ResourceModelCall expected) throws SQLException {
        try(var s=c.prepareStatement("""
                select r.*,e.execution_epoch,e.entry_key as retained_entry,e.entering_executor as retained_executor,
                    e.call_key as retained_call,e.contract_fingerprint as retained_contract
                from task.model_call_reservation r left join task.resource_execution e on e.entry_key=r.entry_key
                where r.reservation_key=?
                """)) {
            s.setString(1,expected.key());
            try(var r=s.executeQuery()) {
                if(!r.next())return Optional.empty();
                if(r.getString("retained_entry")==null || !expected.entry().callKey().equals(r.getString("call_key"))
                        || !expected.entry().callKey().equals(r.getString("retained_call")))throw conflict();
                var task=JdbcResourceTaskAllocations.read(c,expected.entry().scope()).orElseThrow(JdbcResourceModelCalls::conflict);
                var entry=new ResourceExecutionEntry(task.allocation().scope(),r.getLong("execution_epoch"));
                UUID executor=r.getObject("executor",UUID.class);
                var call=new ResourceModelCall(entry,executor,r.getString("node_occurrence"),r.getString("request_fingerprint"),r.getInt("max_output_tokens"));
                var account=account(c,r.getString("account_key")).orElseThrow(JdbcResourceModelCalls::conflict);
                var value=new ModelCallReservation(call,task,account,r.getLong("held_micros"),r.getLong("allocation_remaining_after"),
                        r.getLong("account_remaining_after"),r.getInt("ordinal"),r.getInt("calls_remaining"),
                        r.getTimestamp("reserved_at").toInstant(),r.getTimestamp("call_deadline").toInstant());
                if(!call.equals(expected) || !call.key().equals(r.getString("reservation_key"))
                        || !call.fingerprint().equals(r.getString("intent_fingerprint"))
                        || !entry.fingerprint().equals(r.getString("entry_key")) || !entry.fingerprint().equals(r.getString("retained_entry"))
                        || !executor.equals(r.getObject("retained_executor",UUID.class))
                        || !entry.scope().fingerprint().equals(r.getString("retained_contract"))
                        || !task.allocation().key().equals(r.getString("allocation_key"))
                        || !task.allocation().fingerprint().equals(r.getString("allocation_fingerprint"))
                        || !account.fingerprint().equals(r.getString("account_fingerprint"))
                        || !value.fingerprint().equals(r.getString("fingerprint")) || r.next())throw conflict();
                return Optional.of(value);
            }
        }
    }
    private <T> T transaction(boolean snapshot,Transactions.Work<T> work) {
        return Transactions.inTransaction(data,c->{
            try(var limits=c.createStatement()) {
                limits.execute("set local search_path = pg_catalog, pg_temp");
                if(snapshot) {
                    String isolation;
                    try(var r=limits.executeQuery("show transaction_isolation")){r.next();isolation=r.getString(1);}
                    limits.execute(isolation.equals("serializable")?"set transaction read only":
                            "set transaction isolation level repeatable read read only");
                }
                limits.execute("""
                    do $$ begin
                        if current_setting('lock_timeout')::interval = interval '0'
                           or current_setting('lock_timeout')::interval > interval '2 seconds' then
                            perform set_config('lock_timeout','2s',true);
                        end if;
                        if current_setting('statement_timeout')::interval = interval '0'
                           or current_setting('statement_timeout')::interval > interval '5 seconds' then
                            perform set_config('statement_timeout','5s',true);
                        end if;
                    end $$
                    """);
            }
            return work.run(c);
        });
    }
    private <T> T suspended(Supplier<T> work) {
        Transaction prior;
        try {prior=transactions.suspend();}
        catch(Exception failed){throw new StoreUnavailableException("caller transaction cannot be suspended",failed);}
        Throwable primary=null;
        try {return work.get();}
        catch(RuntimeException|Error failed){primary=failed;throw failed;}
        finally {if(prior!=null)try{transactions.resume(prior);}catch(Exception failed){
            if(primary==null)throw new StoreUnavailableException("caller transaction cannot be resumed",failed);
            if(primary!=failed)primary.addSuppressed(failed);
        }}
    }
    private static void authority(ResourceModelCall call,ResourceExecutionFence current) {
        interrupted();current.requireCurrent(call.entry());interrupted();
    }
    private static void interrupted() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException("model invocation interrupted; retain original reservation");
    }
    private static boolean sqlState(Throwable failure,String expected) {
        for(Throwable t=failure;t!=null;t=t.getCause())if(t instanceof SQLException sql && expected.equals(sql.getSQLState()))return true;
        return false;
    }
    private static IllegalStateException conflict(){return new IllegalStateException("model reservation metadata is closed, changed or inconsistent");}
}
