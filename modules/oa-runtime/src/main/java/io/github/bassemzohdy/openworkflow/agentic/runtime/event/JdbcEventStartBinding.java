package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Proposed V83 transaction primitive only. Not an ingress, authorization check, launch, or production bean. */
public final class JdbcEventStartBinding {
    private JdbcEventStartBinding() { }

    /** Invoke only in the new-instance insert hook after creating the original authority on the same transaction. */
    public static void insert(Connection c, Ulid instance, EventStartBinding b) throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(instance); Objects.requireNonNull(b);
        if (c.getAutoCommit()) throw new IllegalArgumentException("an existing transaction is required");
        try (var q = c.prepareStatement("insert into task.event_start_binding "
                + "(tenant,start_key,instance_id,request_fingerprint,owner_kind,owner_id,initial_grant_id,binding_fingerprint) "
                + "values (?,?,?,?,?,?,?,?)")) {
            q.setString(1,b.tenant()); q.setString(2,b.startKey()); q.setString(3,instance.value());
            q.setString(4,b.requestFingerprint()); q.setString(5,b.owner().kind().name()); q.setString(6,b.owner().id());
            q.setString(7,b.originalGrant().value()); q.setString(8,b.fingerprint()); q.executeUpdate();
        }
    }

    /** Exact retained-data comparison after duplicate insertion; callers must separately reauthorize before readback. */
    public static boolean matches(Connection c, Ulid instance, EventStartBinding b) throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(instance); Objects.requireNonNull(b);
        try (var q = c.prepareStatement("select 1 from task.event_start_binding b "
                + "join task.instance i on i.id=b.instance_id join identity.instance_authority a on a.instance_id=i.id "
                + "where b.tenant=? and b.start_key=? and b.instance_id=? and b.request_fingerprint=? "
                + "and b.owner_kind=? and b.owner_id=? and b.initial_grant_id=? and b.binding_fingerprint=? "
                + "and i.tenant=b.tenant and i.idempotency_key=b.start_key and i.request_fingerprint=b.request_fingerprint "
                + "and i.owner_kind=b.owner_kind and i.owner_id=b.owner_id and a.tenant=b.tenant "
                + "and a.subject_kind=b.owner_kind and a.subject_id=b.owner_id and a.initial_grant_known "
                + "and a.initial_grant_id=b.initial_grant_id")) {
            q.setString(1,b.tenant()); q.setString(2,b.startKey()); q.setString(3,instance.value());
            q.setString(4,b.requestFingerprint()); q.setString(5,b.owner().kind().name()); q.setString(6,b.owner().id());
            q.setString(7,b.originalGrant().value()); q.setString(8,b.fingerprint());
            try (var r=q.executeQuery()) { return r.next(); }
        }
    }
}
