package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.common.CanonicalEncoder;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditSource;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResourceEntryAuditSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceEntryAuditOriginMigrationTest {
    @Test void upgradePreservesSourcesButCannotBackfillHistoricalEntries() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate("77.10");
            var retained = fixture(db, "a".repeat(64));
            var legacy = fixture(db, "b".repeat(64));
            try (var gate = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource())) {
                gate.open(retained.binding(), retained.entry(1), UUID.randomUUID(), ignored -> {}).execute(ignored -> "done");
            }
            try (var gate = new JdbcResourceExecutionGate(db.owner, 1)) {
                gate.open(legacy.binding(), legacy.entry(1), UUID.randomUUID(), ignored -> {}).execute(ignored -> "done");
            }
            db.migrate(null);
            try (var gate = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource())) {
                assertTrue(gate.findEntryAudit(retained.entry(1).scope()).isPresent());
                assertTrue(gate.findEntryAudit(legacy.entry(1).scope()).isEmpty());
            }
            try (var c = db.owner.getConnection()) {
                var failure = assertThrows(SQLException.class, () -> insertSource(c, legacy, false, false));
                assertEquals("23514", failure.getSQLState());
            }
            var laterLegacy = fixture(db, "c".repeat(64));
            try (var gate = new JdbcResourceExecutionGate(db.owner, 1)) {
                gate.open(laterLegacy.binding(), laterLegacy.entry(1), UUID.randomUUID(), ignored -> {}).execute(ignored -> "done");
            }
            try (var c = db.owner.getConnection()) {
                var failure = assertThrows(SQLException.class, () -> insertSource(c, laterLegacy, false, false));
                assertEquals("23514", failure.getSQLState());
            }
            assertEquals(1, count(db.owner, "select count(*) from task.resource_execution_audit_source"));
        }
    }

    @Test void sameTransactionMustMatchEntryAndCallerCannotChooseOrigin() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);
            var f = fixture(db, "a".repeat(64));
            try (var c = db.owner.getConnection()) {
                c.setAutoCommit(false);
                try (var q = c.prepareStatement("""
                        insert into task.resource_execution(call_key,entry_key,contract_fingerprint,execution_epoch,
                            first_binding_key,entering_executor,audit_origin_transaction)
                        values(?,?,?,?,?,?,'3'::xid8)
                        """)) {
                    q.setString(1, f.entry(1).callKey()); q.setString(2, f.entry(1).fingerprint());
                    q.setString(3, f.entry(1).scope().fingerprint()); q.setLong(4, 1);
                    q.setString(5, f.binding().key()); q.setObject(6, UUID.randomUUID()); q.executeUpdate();
                }
                try (var q = c.createStatement(); var r = q.executeQuery(
                        "select audit_origin_transaction=pg_current_xact_id() from task.resource_execution")) {
                    assertTrue(r.next()); assertTrue(r.getBoolean(1));
                }
                for (var badScope : new boolean[]{true, false}) {
                    var savepoint = c.setSavepoint();
                    var failure = assertThrows(SQLException.class, () -> insertSource(c, f, badScope, !badScope));
                    assertEquals("23514", failure.getSQLState());
                    c.rollback(savepoint);
                }
                insertSource(c, f, false, false);
                c.commit();
            }
            assertEquals(1, count(db.owner, "select count(*) from task.resource_execution_audit_source"));
        }
    }

    private static void insertSource(Connection c, ResourceExecutionMigrationTest.Fixture f,
                                     boolean badScope, boolean badKey) throws SQLException {
        String scope = badScope ? "f".repeat(64) : f.entry(1).scope().fingerprint();
        String key = badKey ? "e".repeat(64) : CanonicalEncoder.sha256Hex(
                new CanonicalEncoder("oa.resource.entry.audit-key.v1").text(f.entry(1).fingerprint()).toBytes());
        var source = new AuditSource(1, scope, key, AuditSource.Transition.RESOURCE_ENTRY, 1, "c".repeat(64));
        try (var q = c.prepareStatement("""
                insert into task.resource_execution_audit_source
                  (entry_key,scope_fingerprint,key_fingerprint,source_key,draft_id,content_fingerprint)
                values (?,?,?,?,?,?)
                """)) {
            q.setString(1, f.entry(1).fingerprint()); q.setString(2, scope); q.setString(3, key);
            q.setString(4, source.key()); q.setString(5, UlidGenerator.shared().next().value());
            q.setString(6, source.contentFingerprint()); q.executeUpdate();
        }
    }
}
