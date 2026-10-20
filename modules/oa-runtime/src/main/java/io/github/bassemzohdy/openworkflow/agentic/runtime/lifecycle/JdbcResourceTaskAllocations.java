package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceTaskAllocations;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Explicit opt-in store for proposed V78; no CDI bean/scheduler/production activation. Allocation DATA is not a
 * model budget reservation or permission. PostgreSQL serializes new allocations with the original registry and
 * validates actual retained call/grant ancestry/parent and immutable contract/profile/lifetime. Historical exact
 * repeats survive credential expiry/registry completion and never reset the original deadline or budget source.
 * Caller JTA is suspended for short owned JDBC transactions; unknown commit/resume errors refuse, never mean absent.
 */
public final class JdbcResourceTaskAllocations implements ResourceTaskAllocations {
    private final DataSource data;
    private final TransactionManager transactions;

    public JdbcResourceTaskAllocations(DataSource data) { this(data, null); }
    public JdbcResourceTaskAllocations(DataSource data, TransactionManager transactions) {
        this.data = Objects.requireNonNull(data, "data");
        this.transactions = transactions;
    }

    @Override public AllocatedResourceTask allocate(ResourceTaskAllocation candidate) {
        Objects.requireNonNull(candidate, "candidate");
        // Existing stored-task wire form is whole seconds. Refuse lossy precision BEFORE I/O.
        String contract = ResourceTaskAllocationCodec.contract(candidate.contract());
        String profile = ResourceTaskAllocationCodec.profile(candidate.modelProfile());
        new DefinitionRef(candidate.scope().namespace() + "/" + candidate.scope().definition(), candidate.scope().version());
        return isolated(connection -> {
            var existing = read(connection, candidate.scope());
            if (existing.isPresent()) return same(existing.get(), candidate);
            try (var lock = connection.prepareStatement("select id from task.instance where id=? for update")) {
                lock.setString(1, candidate.scope().instanceId().value());
                try (var row = lock.executeQuery()) { if (!row.next()) throw conflict(); }
            }
            existing = read(connection, candidate.scope());
            if (existing.isPresent()) return same(existing.get(), candidate);
            try (var insert = connection.prepareStatement("""
                    insert into task.resource_task_allocation(allocation_key,call_key,call_fingerprint,parent_task_id,
                        contract_id,contract,contract_fingerprint,model_profile,profile_fingerprint,fingerprint)
                    values(?,?,?,?,?,cast(? as jsonb),?,cast(? as jsonb),?,?) on conflict(call_key) do nothing
                    """)) {
                insert.setString(1, candidate.key()); insert.setString(2, candidate.scope().key());
                insert.setString(3, candidate.scope().fingerprint());
                insert.setString(4, candidate.parentTaskId() == null ? null : candidate.parentTaskId().value());
                insert.setString(5, candidate.contract().id().value()); insert.setString(6, contract);
                insert.setString(7, AgentTaskContractFingerprint.of(candidate.contract())); insert.setString(8, profile);
                insert.setString(9, candidate.modelProfile().fingerprint()); insert.setString(10, candidate.fingerprint());
                insert.executeUpdate();
            }
            return same(read(connection, candidate.scope()).orElseThrow(JdbcResourceTaskAllocations::conflict), candidate);
        });
    }

    @Override public Optional<AllocatedResourceTask> find(ResourceCallScope scope) {
        Objects.requireNonNull(scope, "scope");
        new DefinitionRef(scope.namespace() + "/" + scope.definition(), scope.version());
        return isolated(connection -> read(connection, scope));
    }

    private static AllocatedResourceTask same(AllocatedResourceTask stored, ResourceTaskAllocation candidate) {
        if (!stored.allocation().equals(candidate)) throw conflict();
        return stored;
    }

    private static Optional<AllocatedResourceTask> read(Connection connection, ResourceCallScope expected) throws SQLException {
        try (var select = connection.prepareStatement("""
                select c.*,o.occurrence_key,a.allocation_key,a.parent_task_id,a.contract_id,
                    case when octet_length(a.contract::text)<=65536 then a.contract::text end as contract_text,
                    a.contract_fingerprint,
                    case when octet_length(a.model_profile::text)<=16384 then a.model_profile::text end as profile_text,
                    a.profile_fingerprint,a.fingerprint as allocation_fingerprint,
                    a.call_fingerprint,a.created_at as allocation_created_at,a.deadline
                from task.resource_task_allocation a left join task.resource_call c on c.call_key=a.call_key
                left join task.resource_occurrence o on o.call_key=c.call_key where a.call_key=?
                """)) {
            select.setString(1, expected.key());
            try (var row = select.executeQuery()) {
                if (!row.next()) return Optional.empty();
                if (row.getString("call_key") == null || row.getString("occurrence_key") == null) throw conflict();
                var scope = new ResourceCallScope(row.getString("tenant"), Ulid.parse(row.getString("instance_id")),
                        new Subject(SubjectKind.valueOf(row.getString("owner_kind")), row.getString("owner_id")),
                        row.getString("application_id"), row.getString("engine_instance_id"), row.getString("namespace"),
                        row.getString("definition_name"), row.getString("definition_version"), row.getString("task_position"),
                        row.getInt("iteration"), row.getString("logical_occurrence"), row.getString("audience"),
                        row.getString("operation"), row.getString("target_fingerprint"), row.getString("request_fingerprint"));
                if (!scope.equals(expected) || !scope.key().equals(row.getString("call_key"))
                        || !scope.fingerprint().equals(row.getString("fingerprint"))
                        || !scope.fingerprint().equals(row.getString("call_fingerprint"))
                        || !scope.occurrenceKey().equals(row.getString("occurrence_key"))) throw conflict();
                String parent = row.getString("parent_task_id");
                var allocation = new ResourceTaskAllocation(scope, parent == null ? null : Ulid.parse(parent),
                        ResourceTaskAllocationCodec.contract(row.getString("contract_text")),
                        ResourceTaskAllocationCodec.profile(row.getString("profile_text")));
                if (!allocation.key().equals(row.getString("allocation_key"))
                        || !allocation.fingerprint().equals(row.getString("allocation_fingerprint"))
                        || !allocation.contract().id().value().equals(row.getString("contract_id"))
                        || !AgentTaskContractFingerprint.of(allocation.contract()).equals(row.getString("contract_fingerprint"))
                        || !allocation.modelProfile().fingerprint().equals(row.getString("profile_fingerprint"))) throw conflict();
                var result = new AllocatedResourceTask(allocation, row.getTimestamp("allocation_created_at").toInstant(),
                        row.getTimestamp("deadline").toInstant());
                if (row.next()) throw conflict();
                return Optional.of(result);
            }
        }
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("resource task allocation conflicts or is inconsistent");
    }

    private <T> T isolated(Transactions.Work<T> work) {
        Transaction suspended = null;
        try {
            if (transactions != null) suspended = transactions.suspend();
        } catch (Exception failed) { throw new StoreUnavailableException("caller transaction cannot be suspended", failed); }
        try {
            return Transactions.inTransaction(data, connection -> {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                return work.run(connection);
            });
        } finally {
            if (suspended != null) try { transactions.resume(suspended); }
            catch (Exception failed) { throw new StoreUnavailableException("caller transaction cannot be resumed", failed); }
        }
    }
}
