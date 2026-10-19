package io.github.bassemzohdy.openworkflow.agentic.runtime.identity;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.Transactions;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Grants over plain JDBC (table {@code identity.delegation_grant}, migration V60). The database repeats the narrowing
 * rules in triggers, so a refused write surfaces here as {@link GrantException} (a narrowing or chain violation is
 * {@code CYCLE} or {@code PARENT_INACTIVE}, and a scope or expiry violation is rethrown as
 * {@link io.github.bassemzohdy.openworkflow.agentic.contracts.agent.PermissionWideningException}).
 */
public class DelegationGrantStore {

    private static final String COLUMNS = """
            id, tenant, subject_kind, subject_id, agent_kind, agent_id, scopes, parent_grant_id, chain, status,
            created_at, created_by_kind, created_by_id, expires_at, revoked_at, revoke_reason, version
            """;

    private static final String NARROWING = "grant narrowing:";

    private final DataSource dataSource;

    public DelegationGrantStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Inserts a new ACTIVE grant (version 0). The database checks the narrowing rules against the parent. */
    public void insert(DelegationGrant grant) {
        Objects.requireNonNull(grant, "grant");
        if (grant.status() != DelegationGrant.Status.ACTIVE || grant.version() != 0) {
            throw new IllegalArgumentException("a new grant is ACTIVE with version 0");
        }
        try {
            Transactions.inTransaction(dataSource, connection -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        insert into identity.delegation_grant (id, tenant, subject_kind, subject_id, agent_kind,
                            agent_id, scopes, parent_grant_id, depth, chain, status, created_at, created_by_kind,
                            created_by_id, expires_at, version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
                        """)) {
                    insert.setString(1, grant.id().value());
                    insert.setString(2, grant.tenant());
                    insert.setString(3, grant.delegator().kind().name());
                    insert.setString(4, grant.delegator().id());
                    insert.setString(5, grant.agent().kind().name());
                    insert.setString(6, grant.agent().id());
                    insert.setArray(7, textArray(connection, grant.scopes().stream().sorted().toList()));
                    insert.setString(8, grant.parentId() == null ? null : grant.parentId().value());
                    insert.setInt(9, grant.chain().size() - 1);
                    insert.setArray(10, textArray(connection, grant.chain()));
                    insert.setTimestamp(11, Timestamp.from(grant.createdAt()));
                    insert.setString(12, grant.createdBy().kind().name());
                    insert.setString(13, grant.createdBy().id());
                    insert.setTimestamp(14, Timestamp.from(grant.expiresAt()));
                    insert.executeUpdate();
                }
                return null;
            });
        } catch (io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.StoreUnavailableException e) {
            if (e.getCause() instanceof SQLException sql && sql.getMessage() != null
                    && sql.getMessage().contains(NARROWING)) {
                throw narrowing(sql.getMessage());
            }
            throw e;
        }
    }

    private static RuntimeException narrowing(String message) {
        if (message.contains("scopes are not a subset") || message.contains("later than the parent")) {
            return new io.github.bassemzohdy.openworkflow.agentic.contracts.agent.PermissionWideningException(
                    "the delegation would widen the parent grant");
        }
        if (message.contains("already in the delegation chain")) {
            return new GrantException(GrantException.Code.CYCLE, "the agent is already in the delegation chain");
        }
        return new GrantException(GrantException.Code.PARENT_INACTIVE, "the parent grant cannot be delegated from");
    }

    public Optional<DelegationGrant> find(Ulid id) {
        Objects.requireNonNull(id, "id");
        return Transactions.inTransaction(dataSource, connection -> find(connection, id));
    }

    private static Optional<DelegationGrant> find(Connection connection, Ulid id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "select " + COLUMNS + " from identity.delegation_grant where id = ?")) {
            select.setString(1, id.value());
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        }
    }

    /** The grants of this user for this agent that are ACTIVE and not expired at {@code now}, newest first. */
    public List<DelegationGrant> activeFor(String tenant, String userId, String agentId, Instant now) {
        return Transactions.inTransaction(dataSource, connection -> {
            try (PreparedStatement select = connection.prepareStatement("select " + COLUMNS + """
                     from identity.delegation_grant
                    where tenant = ? and subject_id = ? and agent_id = ? and status = 'ACTIVE' and expires_at > ?
                    order by id desc
                    """)) {
                select.setString(1, tenant);
                select.setString(2, userId);
                select.setString(3, agentId);
                select.setTimestamp(4, Timestamp.from(now));
                List<DelegationGrant> grants = new ArrayList<>();
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        grants.add(read(rs));
                    }
                }
                return grants;
            }
        });
    }

    /**
     * Revokes the grant and every grant below it that is still ACTIVE, in one transaction.
     *
     * @return the grants that were revoked by this call (empty when none was ACTIVE), root first
     */
    public List<DelegationGrant> revokeSubtree(Ulid root, Instant at, String revokedBy, String reason) {
        return Transactions.inTransaction(dataSource, connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    with recursive tree as (
                        select id, 0 as level from identity.delegation_grant where id = ?
                        union all
                        select g.id, t.level + 1 from identity.delegation_grant g join tree t on g.parent_grant_id = t.id
                    )
                    select g.id from identity.delegation_grant g join tree t on t.id = g.id
                     where g.status = 'ACTIVE' order by t.level, g.id for update of g
                    """)) {
                select.setString(1, root.value());
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            List<DelegationGrant> revoked = new ArrayList<>();
            for (String id : ids) {
                try (PreparedStatement update = connection.prepareStatement("""
                        update identity.delegation_grant
                           set status = 'REVOKED', revoked_at = ?, revoked_by_id = ?, revoke_reason = ?,
                               version = version + 1
                         where id = ? and status = 'ACTIVE'
                        """)) {
                    update.setTimestamp(1, Timestamp.from(at));
                    update.setString(2, revokedBy);
                    update.setString(3, reason);
                    update.setString(4, id);
                    if (update.executeUpdate() == 1) {
                        revoked.add(find(connection, new Ulid(id)).orElseThrow());
                    }
                }
            }
            return revoked;
        });
    }

    /** Marks ACTIVE grants whose expiry is at or before {@code now} as EXPIRED. Returns them (at most {@code limit}). */
    public List<DelegationGrant> markExpired(Instant now, int limit) {
        return Transactions.inTransaction(dataSource, connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    select id from identity.delegation_grant
                     where status = 'ACTIVE' and expires_at <= ? order by expires_at limit ? for update skip locked
                    """)) {
                select.setTimestamp(1, Timestamp.from(now));
                select.setInt(2, limit);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            List<DelegationGrant> expired = new ArrayList<>();
            for (String id : ids) {
                try (PreparedStatement update = connection.prepareStatement("""
                        update identity.delegation_grant set status = 'EXPIRED', version = version + 1
                         where id = ? and status = 'ACTIVE'
                        """)) {
                    update.setString(1, id);
                    if (update.executeUpdate() == 1) {
                        expired.add(find(connection, new Ulid(id)).orElseThrow());
                    }
                }
            }
            return expired;
        });
    }

    private static Array textArray(Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray(new String[0]));
    }

    private static DelegationGrant read(ResultSet rs) throws SQLException {
        String parent = rs.getString("parent_grant_id");
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new DelegationGrant(
                new Ulid(rs.getString("id")),
                rs.getString("tenant"),
                new Subject(SubjectKind.valueOf(rs.getString("subject_kind")), rs.getString("subject_id")),
                new Subject(SubjectKind.valueOf(rs.getString("agent_kind")), rs.getString("agent_id")),
                new LinkedHashSet<>(Arrays.asList((String[]) rs.getArray("scopes").getArray())),
                parent == null ? null : new Ulid(parent),
                Arrays.asList((String[]) rs.getArray("chain").getArray()),
                DelegationGrant.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                new Subject(SubjectKind.valueOf(rs.getString("created_by_kind")), rs.getString("created_by_id")),
                rs.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant(),
                rs.getString("revoke_reason"),
                rs.getLong("version"));
    }
}
