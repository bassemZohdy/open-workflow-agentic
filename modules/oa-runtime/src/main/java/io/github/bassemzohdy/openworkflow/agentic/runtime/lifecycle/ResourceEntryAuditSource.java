package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Retains an entry's audit draft in the same transaction as its fresh entry. Data, not permission or a receipt. */
public final class ResourceEntryAuditSource {
    private static final Subject ACTOR = new Subject(SubjectKind.SERVICE, "system:resource-entry");
    public record Retained(AuditSource source, AuditEvent draft) { }

    void captureFresh(Connection c, JdbcResourceExecutionGate.EntryFact fact) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("entry audit requires an owned transaction");
        var value = build(fact, UlidGenerator.shared().next());
        try (var q = c.prepareStatement("""
                insert into task.resource_execution_audit_source
                  (entry_key,scope_fingerprint,key_fingerprint,source_key,draft_id,content_fingerprint)
                values (?,?,?,?,?,?)
                """)) {
            q.setString(1, fact.entry().fingerprint());
            q.setString(2, value.source().scopeFingerprint());
            q.setString(3, value.source().keyFingerprint());
            q.setString(4, value.source().key());
            q.setString(5, value.draft().id().value());
            q.setString(6, value.source().contentFingerprint());
            q.executeUpdate();
        }
    }

    Optional<Retained> read(Connection c, JdbcResourceExecutionGate.EntryFact fact) throws SQLException {
        Objects.requireNonNull(fact);
        try (var q = c.prepareStatement("""
                select scope_fingerprint,key_fingerprint,source_key,draft_id,content_fingerprint
                from task.resource_execution_audit_source where entry_key=?
                """)) {
            q.setString(1, fact.entry().fingerprint());
            try (var r = q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var value = build(fact, Ulid.parse(r.getString(4)));
                if (!value.source().scopeFingerprint().equals(r.getString(1))
                        || !value.source().keyFingerprint().equals(r.getString(2))
                        || !value.source().key().equals(r.getString(3))
                        || !value.source().contentFingerprint().equals(r.getString(5)))
                    throw new IllegalStateException("retained entry audit source differs");
                return Optional.of(value);
            }
        }
    }

    private static Retained build(JdbcResourceExecutionGate.EntryFact fact, Ulid id) {
        var entry = fact.entry();
        String scope = entry.scope().fingerprint();
        String key = CanonicalEncoder.sha256Hex(new CanonicalEncoder("oa.resource.entry.audit-key.v1")
                .text(entry.fingerprint()).toBytes());
        var preliminary = new AuditSource(1, scope, key, AuditSource.Transition.RESOURCE_ENTRY, 1, "0".repeat(64));
        var draft = new AuditEvent(id, 1, fact.enteredAt(), ACTOR, "resource.execution.entered",
                "resource-execution", entry.fingerprint(), Map.of("entry.source", preliminary.key(),
                "entry.call", entry.callKey(), "entry.binding", fact.firstBindingKey(),
                "entry.executor", fact.enteringExecutor().toString()), AuditHash.GENESIS, AuditHash.GENESIS);
        var source = new AuditSource(1, scope, key, AuditSource.Transition.RESOURCE_ENTRY, 1,
                AuditDraftFingerprint.of(draft));
        return new Retained(source, draft);
    }
}
