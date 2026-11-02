package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.Checks;
import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Proposed V83 transaction primitive only. Not an ingress, authorization check, launch, or production bean. */
public final class JdbcEventStartBinding {
    private JdbcEventStartBinding() { }

    /** Invoke only in the new-instance insert hook after creating the original authority on the same transaction. */
    public static void insert(Connection c, Ulid instance, EventStartBinding b, Duration retention) throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(instance); Objects.requireNonNull(b); Objects.requireNonNull(retention);
        if (retention.isNegative() || retention.isZero() || retention.getNano()!=0
                || retention.compareTo(Duration.ofDays(30))>0)
            throw new IllegalArgumentException("retention must be whole seconds between 1 second and 30 days");
        if (c.getAutoCommit()) throw new IllegalArgumentException("an existing transaction is required");
        try (var q = c.prepareStatement("insert into task.event_start_binding "
                + "(tenant,start_key,instance_id,request_fingerprint,owner_kind,owner_id,initial_grant_id,binding_fingerprint,"
                + "producer_kind,producer_id,binding_id,binding_version,event_envelope,retain_until) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,statement_timestamp()+(? * interval '1 second'))")) {
            q.setString(1,b.tenant()); q.setString(2,b.startKey()); q.setString(3,instance.value());
            q.setString(4,b.requestFingerprint()); q.setString(5,b.owner().kind().name()); q.setString(6,b.owner().id());
            q.setString(7,b.originalGrant().value()); q.setString(8,b.fingerprint());
            q.setString(9,b.producer().kind().name()); q.setString(10,b.producer().id()); q.setString(11,b.bindingId());
            q.setString(12,b.bindingVersion()); q.setString(13,b.event().canonicalEnvelope());
            q.setLong(14,retention.toSeconds()); q.executeUpdate();
        }
    }

    /** A tombstone exists, but its original payload has been removed. */
    public static final class ReceiptRedacted extends SQLException {
        public ReceiptRedacted() { super("event start payload was redacted"); }
    }

    /** Retained data only. It is not permission to disclose, recover, or launch an instance. */
    public record Retained(Ulid instance, EventStartBinding binding) {
        public Retained { Objects.requireNonNull(instance); Objects.requireNonNull(binding); }
        @Override public String toString() { return "EventStartReceipt[redacted]"; }
    }

    /**
     * Recover the original bounded event and attribution after checking them against the instance, original authority,
     * event identity and retained fingerprint. A caller must establish current authority before reading or using it.
     */
    public static Optional<Retained> read(Connection c, String tenant, String startKey) throws SQLException {
        Objects.requireNonNull(c);
        Checks.requireText("tenant", tenant, 256); CanonicalEncoder.strictUtf8(tenant);
        if (startKey == null || !startKey.matches("event-[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid event start key");
        try (var q = c.prepareStatement("select b.instance_id,b.request_fingerprint,b.owner_kind,b.owner_id,"
                + "b.initial_grant_id,b.binding_fingerprint,b.producer_kind,b.producer_id,b.binding_id,b.binding_version,"
                + "b.event_envelope,(i.tenant=b.tenant and i.idempotency_key=b.start_key "
                + "and i.request_fingerprint=b.request_fingerprint and i.owner_kind=b.owner_kind and i.owner_id=b.owner_id "
                + "and a.tenant=b.tenant and a.subject_kind=b.owner_kind and a.subject_id=b.owner_id "
                + "and a.initial_grant_known and a.initial_grant_id=b.initial_grant_id) as valid "
                + "from task.event_start_binding b left join task.instance i on i.id=b.instance_id "
                + "left join identity.instance_authority a on a.instance_id=b.instance_id "
                + "where b.tenant=? and b.start_key=?")) {
            q.setString(1,tenant); q.setString(2,startKey);
            try (var r=q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                if (!r.getBoolean(12)) throw new SQLException("retained event start no longer matches instance authority");
                if (r.getString(11)==null) throw new ReceiptRedacted();
                try {
                    String envelope = r.getString(11);
                    var event = StructuredEvent.read(new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)));
                    if (!event.canonicalEnvelope().equals(envelope)) throw new IllegalArgumentException("noncanonical event");
                    var binding = EventStartBinding.restore(event,tenant,startKey,r.getString(2),
                            new Subject(SubjectKind.valueOf(r.getString(3)),r.getString(4)),
                            new Subject(SubjectKind.valueOf(r.getString(7)),r.getString(8)),Ulid.parse(r.getString(5)),
                            r.getString(9),r.getString(10));
                    if (!binding.fingerprint().equals(r.getString(6))) throw new IllegalArgumentException("binding mismatch");
                    return Optional.of(new Retained(Ulid.parse(r.getString(1)),binding));
                } catch (RuntimeException invalid) {
                    throw new SQLException("invalid retained event start binding", invalid);
                }
            }
        }
    }

    /** Exact retained-data comparison after duplicate insertion; callers must separately reauthorize before readback. */
    public static boolean matches(Connection c, Ulid instance, EventStartBinding b) throws SQLException {
        Objects.requireNonNull(instance); Objects.requireNonNull(b);
        try {
            var retained = read(c,b.tenant(),b.startKey());
            return retained.isPresent() && retained.get().instance().equals(instance)
                    && retained.get().binding().fingerprint().equals(b.fingerprint());
        } catch (ReceiptRedacted redacted) {
            // A tombstone still pins identity and the original contract, but cannot recover the payload.
            try (var q=c.prepareStatement("select 1 from task.event_start_binding b "
                    + "join task.instance i on i.id=b.instance_id join identity.instance_authority a on a.instance_id=i.id "
                    + "where b.tenant=? and b.start_key=? and b.instance_id=? and b.request_fingerprint=? "
                    + "and b.owner_kind=? and b.owner_id=? and b.initial_grant_id=? and b.binding_fingerprint=? "
                    + "and b.producer_kind=? and b.producer_id=? and b.binding_id=? and b.binding_version=? "
                    + "and b.event_envelope is null and b.redacted_at is not null and i.tenant=b.tenant "
                    + "and i.idempotency_key=b.start_key and i.request_fingerprint=b.request_fingerprint "
                    + "and i.owner_kind=b.owner_kind and i.owner_id=b.owner_id and a.tenant=b.tenant "
                    + "and a.subject_kind=b.owner_kind and a.subject_id=b.owner_id and a.initial_grant_known "
                    + "and a.initial_grant_id=b.initial_grant_id")) {
                q.setString(1,b.tenant());q.setString(2,b.startKey());q.setString(3,instance.value());
                q.setString(4,b.requestFingerprint());q.setString(5,b.owner().kind().name());q.setString(6,b.owner().id());
                q.setString(7,b.originalGrant().value());q.setString(8,b.fingerprint());
                q.setString(9,b.producer().kind().name());q.setString(10,b.producer().id());
                q.setString(11,b.bindingId());q.setString(12,b.bindingVersion());
                try(var r=q.executeQuery()){return r.next();}
            }
        }
    }

    /** Remove only an expired payload for a terminal instance; the immutable identity tombstone remains. */
    public static boolean redact(Connection c, String tenant, Ulid instance) throws SQLException {
        Objects.requireNonNull(c);Objects.requireNonNull(instance);
        Checks.requireText("tenant",tenant,256);CanonicalEncoder.strictUtf8(tenant);
        if(c.getAutoCommit())throw new IllegalArgumentException("an existing transaction is required");
        // Match registry-first lock order used by lifecycle and instance creation.
        try(var q=c.prepareStatement("select status from task.instance where tenant=? and id=? for update")) {
            q.setString(1,tenant);q.setString(2,instance.value());
            try(var r=q.executeQuery()) {
                if(!r.next() || !java.util.Set.of("COMPLETED","FAILED","CANCELLED").contains(r.getString(1)))return false;
            }
        }
        try(var q=c.prepareStatement("update task.event_start_binding set event_envelope=null,redacted_at=clock_timestamp() "
                + "where tenant=? and instance_id=? and event_envelope is not null and retain_until<=clock_timestamp()")) {
            q.setString(1,tenant);q.setString(2,instance.value());return q.executeUpdate()==1;
        }
    }
}
