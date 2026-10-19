package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** JDBC access to {@code identity.instance_authority}. Identifiers only; see V62. */
public class InstanceAuthorityStore {

    private static final String COLUMNS = """
            instance_id, tenant, subject_kind, subject_id, grant_id, correlation_id, state, state_reason, state_since,
            created_at""";

    private final DataSource dataSource;

    public InstanceAuthorityStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Inserts the row on the caller's connection, so it commits or rolls back with the instance row. */
    public void insert(Connection connection, InstanceAuthority authority) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                insert into identity.instance_authority (instance_id, tenant, subject_kind, subject_id, grant_id,
                    correlation_id, state, state_reason, state_since, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, authority.instanceId().value());
            ps.setString(2, authority.tenant());
            ps.setString(3, authority.subject().kind().name());
            ps.setString(4, authority.subject().id());
            ps.setString(5, authority.grantId() == null ? null : authority.grantId().value());
            ps.setString(6, authority.correlationId());
            ps.setString(7, authority.state().name());
            ps.setString(8, authority.stateReason());
            ps.setTimestamp(9, Timestamp.from(authority.stateSince()));
            ps.setTimestamp(10, Timestamp.from(authority.createdAt()));
            ps.setTimestamp(11, Timestamp.from(authority.createdAt()));
            ps.executeUpdate();
        }
    }

    /** The authority of a registry instance, or empty when the instance carries none. */
    public Optional<InstanceAuthority> find(Ulid instanceId) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select " + COLUMNS + " from identity.instance_authority where instance_id = ?")) {
                ps.setString(1, instanceId.value());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * The authority of the instance that the engine knows under {@code engineInstanceId}. This is how the identity is
     * re-established when a task calls back with the engine's id (the {@code X-Flow-Instance-Id} header).
     */
    public Optional<InstanceAuthority> findByEngineInstanceId(String engineInstanceId) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    select a.instance_id, a.tenant, a.subject_kind, a.subject_id, a.grant_id, a.correlation_id,
                           a.state, a.state_reason, a.state_since, a.created_at
                      from identity.instance_authority a
                      join task.instance i on i.id = a.instance_id
                     where i.engine_instance_id = ?
                    """)) {
                ps.setString(1, engineInstanceId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Marks the instance as needing re-authorization. Only a row that is AUTHORIZED changes; a row that already waits
     * keeps its first reason and time.
     *
     * @return true if this call changed the row
     */
    public boolean markReauthorizationRequired(Ulid instanceId, String reason, Instant at) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    update identity.instance_authority
                       set state = 'REAUTHORIZATION_REQUIRED', state_reason = ?, state_since = ?, updated_at = ?
                     where instance_id = ? and state = 'AUTHORIZED' and grant_id is not null
                    """)) {
                ps.setString(1, reason);
                ps.setTimestamp(2, Timestamp.from(at));
                ps.setTimestamp(3, Timestamp.from(at));
                ps.setString(4, instanceId.value());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /**
     * Binds a new grant and sets the state back to AUTHORIZED, only if the instance currently waits for re-authorization
     * and runs under {@code expectedOldGrant}. Compare-and-set, so two people cannot both rebind.
     *
     * @return true if this call rebound the instance
     */
    public boolean rebind(Ulid instanceId, Ulid expectedOldGrant, Ulid newGrant, Instant at) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    update identity.instance_authority
                       set grant_id = ?, state = 'AUTHORIZED', state_reason = null, state_since = ?, updated_at = ?
                     where instance_id = ? and grant_id = ? and state = 'REAUTHORIZATION_REQUIRED'
                    """)) {
                ps.setString(1, newGrant.value());
                ps.setTimestamp(2, Timestamp.from(at));
                ps.setTimestamp(3, Timestamp.from(at));
                ps.setString(4, instanceId.value());
                ps.setString(5, expectedOldGrant.value());
                return ps.executeUpdate() == 1;
            }
        });
    }

    private static InstanceAuthority read(ResultSet rs) throws SQLException {
        String grant = rs.getString("grant_id");
        return new InstanceAuthority(Ulid.parse(rs.getString("instance_id")), rs.getString("tenant"),
                new Subject(SubjectKind.valueOf(rs.getString("subject_kind")), rs.getString("subject_id")),
                grant == null ? null : Ulid.parse(grant), rs.getString("correlation_id"),
                InstanceAuthority.State.valueOf(rs.getString("state")), rs.getString("state_reason"),
                rs.getTimestamp("state_since").toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
