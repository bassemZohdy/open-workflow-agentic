package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceCallBindings;
import io.github.bassemzohdy.openworkflow.agentic.contracts.port.ResourceInvocations;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Immutable resource-call attribution, not an execution/authorization/replay service. New bindings require the current
 * registry full owner/definition/engine and exact V65 issuing metadata. Historical reads/repeats do not refresh authority.
 * The trusted producer still validates actual SDK/task/registered target, mandatory audit and current authority AFTER
 * blocking storage, before releasing a bearer. Nothing here starts a workflow, HTTP call, graph or effect.
 *
 * <p>Both rows commit in one short owned transaction, suspending ambient JTA. Unknown commit/resume replies refuse to
 * return a binding; reconcile the SAME issuer/jti/call. No raw bearer/request is stored. PostgreSQL timestamps retain
 * microseconds: unsupported precision/range is rejected before I/O rather than silently rounded.
 */
@ApplicationScoped
@Typed({JdbcResourceCallBindings.class, ResourceInvocations.class})
public class JdbcResourceCallBindings implements ResourceInvocations {
    private static final String COLUMNS = """
            call_key,fingerprint,tenant,instance_id,owner_kind,owner_id,application_id,engine_instance_id,
            namespace,definition_name,definition_version,task_position,iteration,logical_occurrence,
            audience,operation,target_fingerprint,request_fingerprint
            """;
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");
    private final DataSource data;
    private final TransactionManager transactions;

    public JdbcResourceCallBindings(DataSource data) { this(data, null); }

    @Inject
    public JdbcResourceCallBindings(DataSource data, TransactionManager transactions) {
        this.data = Objects.requireNonNull(data, "data");
        this.transactions = transactions;
    }

    /**
     * Legacy admission uses the durable store unless a test/deployment supplies its own port bean.
     * Keep the concrete store and canonical producer port available independently of that override.
     * Producing the narrower interface avoids ambiguous CDI resolution without changing owned consumers.
     */
    @Produces
    @Singleton
    @DefaultBean
    ResourceCallBindings admissionBindings() { return this; }

    @Override public ResourceCallBinding bind(ResourceCallBinding binding) {
        Objects.requireNonNull(binding, "binding");
        timestamp(binding.issuedAt()); timestamp(binding.expiresAt());
        var scope = binding.scope();
        new DefinitionRef(scope.namespace()+"/"+scope.definition(), scope.version());
        return isolated(c -> {
            // Exact historical repeats remain data even after registry completion or signed expiry.
            var existing = read(c, binding.issuer(), binding.tokenId());
            if (existing.isPresent()) return same(existing.get(), binding);
            lockRegistry(c, scope);
            // A competing same-registry writer may have committed while we waited for the row lock.
            existing = read(c, binding.issuer(), binding.tokenId());
            if (existing.isPresent()) return same(existing.get(), binding);
            requireRegistry(c, scope);
            var retained=findInvocation(c,scope);
            if(retained.isPresent() && !retained.get().equals(scope)) throw conflict();
            try (var s = c.prepareStatement("insert into task.resource_call ("+COLUMNS+") values ("+
                    "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(call_key) do nothing")) {
                writeScope(s, scope); s.executeUpdate();
            }
            try (var s = c.prepareStatement("""
                    select c.*,o.occurrence_key from task.resource_call c
                    left join task.resource_occurrence o on o.call_key=c.call_key where c.call_key=?
                    """)) {
                s.setString(1, scope.key());
                try (var r=s.executeQuery()) {
                    if (!r.next() || !scope.equals(scope(r))) throw conflict();
                }
            }
            try (var s=c.prepareStatement("""
                    insert into task.resource_call_binding(binding_key,issuer,token_id,call_key,grant_id,issued_at,expires_at)
                    values(?,?,?,?,?,?,?) on conflict do nothing
                    """)) {
                s.setString(1,binding.key()); s.setString(2,binding.issuer()); s.setString(3,binding.tokenId());
                s.setString(4,scope.key()); s.setString(5,binding.issuingGrantId().value());
                s.setTimestamp(6,Timestamp.from(binding.issuedAt())); s.setTimestamp(7,Timestamp.from(binding.expiresAt()));
                s.executeUpdate();
            }
            return same(read(c,binding.issuer(),binding.tokenId()).orElseThrow(JdbcResourceCallBindings::conflict),binding);
        });
    }

    @Override public Optional<ResourceCallBinding> find(String issuer, String tokenId) {
        identity(issuer); identity(tokenId);
        return isolated(c -> read(c,issuer,tokenId));
    }

    @Override public Optional<ResourceCallScope> findInvocation(ResourceCallScope candidate) {
        Objects.requireNonNull(candidate,"candidate");
        new DefinitionRef(candidate.namespace()+"/"+candidate.definition(),candidate.version());
        return isolated(c -> findInvocation(c,candidate));
    }

    private static Optional<ResourceCallScope> findInvocation(Connection c,ResourceCallScope candidate) throws SQLException {
        try(var s=c.prepareStatement("""
                select c.*,o.occurrence_key from task.resource_occurrence o
                join task.resource_call c on c.call_key=o.call_key where o.occurrence_key=?
                """)) {
            s.setString(1,candidate.occurrenceKey());
            try(var r=s.executeQuery()) {
                if(!r.next()) return Optional.empty();
                var stored=scope(r);
                var originalCounter=new ResourceCallScope(candidate.tenant(),candidate.instanceId(),candidate.owner(),
                        candidate.applicationId(),candidate.engineInstanceId(),candidate.namespace(),candidate.definition(),
                        candidate.version(),candidate.taskPosition(),stored.iteration(),candidate.logicalOccurrence(),
                        candidate.audience(),candidate.operation(),candidate.targetFingerprint(),candidate.requestFingerprint());
                if(!stored.equals(originalCounter)) throw conflict();
                return Optional.of(stored);
            }
        }
    }

    private static void identity(String value) {
        Checks.requireText("verified credential identity",value,300); CanonicalEncoder.strictUtf8(value);
    }
    private static void timestamp(Instant value) {
        if (value.getNano()%1000!=0 || value.isBefore(MIN) || value.isAfter(MAX))
            throw new IllegalArgumentException("resource binding timestamp exceeds PostgreSQL precision/range");
    }
    private static ResourceCallBinding same(ResourceCallBinding stored, ResourceCallBinding expected) {
        if (!stored.equals(expected)) throw conflict();
        return stored;
    }
    private static IllegalStateException conflict() {
        return new IllegalStateException("resource call attribution conflicts or is inconsistent");
    }

    private static void lockRegistry(Connection c, ResourceCallScope scope) throws SQLException {
        try(var s=c.prepareStatement("select id from task.instance where id=? for update")) {
            s.setString(1,scope.instanceId().value());
            try(var r=s.executeQuery()) { if(!r.next()) throw conflict(); }
        }
    }
    private static void requireRegistry(Connection c, ResourceCallScope scope) throws SQLException {
        try(var s=c.prepareStatement("""
                select tenant,owner_kind,owner_id,definition_id,definition_version,engine_instance_id,status
                from task.instance where id=?
                """)) {
            s.setString(1,scope.instanceId().value());
            try(var r=s.executeQuery()) {
                if(!r.next() || !scope.tenant().equals(r.getString(1)) || !scope.owner().kind().name().equals(r.getString(2))
                   || !scope.owner().id().equals(r.getString(3))
                   || !(scope.namespace()+"/"+scope.definition()).equals(r.getString(4))
                   || !scope.version().equals(r.getString(5)) || !scope.engineInstanceId().equals(r.getString(6))
                   || !("RUNNING".equals(r.getString(7)) || "WAITING".equals(r.getString(7)))) throw conflict();
            }
        }
    }
    private static void writeScope(PreparedStatement s, ResourceCallScope v) throws SQLException {
        s.setString(1,v.key()); s.setString(2,v.fingerprint()); s.setString(3,v.tenant());
        s.setString(4,v.instanceId().value()); s.setString(5,v.owner().kind().name()); s.setString(6,v.owner().id());
        s.setString(7,v.applicationId()); s.setString(8,v.engineInstanceId()); s.setString(9,v.namespace());
        s.setString(10,v.definition()); s.setString(11,v.version()); s.setString(12,v.taskPosition());
        s.setInt(13,v.iteration()); s.setString(14,v.logicalOccurrence()); s.setString(15,v.audience());
        s.setString(16,v.operation()); s.setString(17,v.targetFingerprint()); s.setString(18,v.requestFingerprint());
    }
    private static ResourceCallScope scope(ResultSet r) throws SQLException {
        var value=new ResourceCallScope(r.getString("tenant"),Ulid.parse(r.getString("instance_id")),
                new Subject(SubjectKind.valueOf(r.getString("owner_kind")),r.getString("owner_id")),
                r.getString("application_id"),r.getString("engine_instance_id"),r.getString("namespace"),
                r.getString("definition_name"),r.getString("definition_version"),r.getString("task_position"),
                r.getInt("iteration"),r.getString("logical_occurrence"),r.getString("audience"),r.getString("operation"),
                r.getString("target_fingerprint"),r.getString("request_fingerprint"));
        if(!value.key().equals(r.getString("call_key")) || !value.fingerprint().equals(r.getString("fingerprint"))
                || !value.occurrenceKey().equals(r.getString("occurrence_key"))) throw conflict();
        return value;
    }
    private static Optional<ResourceCallBinding> read(Connection c, String issuer, String tokenId) throws SQLException {
        try(var s=c.prepareStatement("""
                select c.*,o.occurrence_key,b.binding_key,b.issuer,b.token_id,b.grant_id,b.issued_at,b.expires_at
                from task.resource_call_binding b join task.resource_call c on c.call_key=b.call_key
                left join task.resource_occurrence o on o.call_key=c.call_key
                where b.issuer=? and b.token_id=?
                """)) {
            s.setString(1,issuer); s.setString(2,tokenId);
            try(var r=s.executeQuery()) {
                if(!r.next()) return Optional.empty();
                var value=new ResourceCallBinding(r.getString("issuer"),r.getString("token_id"),
                        Ulid.parse(r.getString("grant_id")),scope(r),r.getTimestamp("issued_at").toInstant(),
                        r.getTimestamp("expires_at").toInstant());
                if(!issuer.equals(value.issuer()) || !tokenId.equals(value.tokenId())
                   || !value.key().equals(r.getString("binding_key"))) throw conflict();
                return Optional.of(value);
            }
        }
    }

    private <T> T isolated(Transactions.Work<T> work) {
        Transaction suspended=null;
        try {
            if(transactions!=null) suspended=transactions.suspend();
        } catch(Exception e) { throw new StoreUnavailableException("caller transaction cannot be suspended",e); }
        try { return Transactions.inTransaction(data,c -> {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return work.run(c);
        }); }
        finally {
            if(suspended!=null) try { transactions.resume(suspended); }
            catch(Exception e) { throw new StoreUnavailableException("caller transaction cannot be resumed",e); }
        }
    }
}
