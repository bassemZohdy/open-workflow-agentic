package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static io.github.bassemzohdy.openworkflow.agentic.lifecycle.ResourceExecutionMigrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditSource;
import io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditAppendOnce;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.ResourceEntryAuditSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceEntryAuditSourceStoreTest {
    @Test void freshEntryRetainsOneStableSourceAndCentralReceipt() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);
            var f = fixture(db, "a".repeat(64));
            var producer = new ResourceEntryAuditSource();
            var central = new AuditAppendOnce(db.owner);
            try (var gate = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), producer, central::appendOnce)) {
                var entry = f.entry(1);
                assertEquals(JdbcResourceExecutionGate.Kind.CALLED,
                        gate.open(f.binding(), entry, UUID.randomUUID(), ignored -> {}).execute(ignored -> {
                            try { assertEquals(1, count(db.owner, "select count(*) from audit.event where event_type='resource.execution.entered'")); }
                            catch (Exception e) { throw new IllegalStateException(e); }
                            return "done";
                        }).kind());
                var source = gate.findEntryAudit(entry.scope()).orElseThrow();
                assertEquals(AuditSource.Transition.RESOURCE_ENTRY, source.source().transition());
                assertEquals("resource.execution.entered", source.draft().action());
                assertEquals(central.appendOnce(source.source(), source.draft()),
                        central.appendOnce(source.source(), source.draft()));
                assertEquals(JdbcResourceExecutionGate.Kind.RETAINED,
                        gate.open(f.binding(), entry, UUID.randomUUID(), ignored -> {}).execute(ignored -> fail("retry called")).kind());
                assertEquals(source, gate.findEntryAudit(entry.scope()).orElseThrow());
                assertEquals(1, count(db.owner, "select count(*) from task.resource_execution_audit_source"));
                assertEquals(1, count(db.owner, "select count(*) from audit.event where event_type='resource.execution.entered'"));
            }
        }
    }

    @Test void centralOutageRetainsEntryAndRefusesCallbackAndFreshRetry() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);
            var f = fixture(db, "a".repeat(64));
            try (var gate = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource(),
                    (source, draft) -> { throw new IllegalStateException("audit unavailable"); })) {
                assertThrows(IllegalStateException.class, () -> gate.open(f.binding(), f.entry(1), UUID.randomUUID(), ignored -> {})
                        .execute(ignored -> fail("callback entered without central audit")));
                assertTrue(gate.findEntryAudit(f.entry(1).scope()).isPresent());
                assertEquals(JdbcResourceExecutionGate.Kind.RETAINED,
                        gate.open(f.binding(), f.entry(1), UUID.randomUUID(), ignored -> {}).execute(ignored -> fail("retry called")).kind());
            }
            try (var recovery = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource(),
                    new AuditAppendOnce(db.owner)::appendOnce)) {
                var receipt = recovery.deliverEntryAudit(f.entry(1).scope());
                assertEquals(receipt, recovery.deliverEntryAudit(f.entry(1).scope()));
                assertEquals(JdbcResourceExecutionGate.Kind.RETAINED,
                        recovery.open(f.binding(), f.entry(1), UUID.randomUUID(), ignored -> {}).execute(ignored -> fail("recovery reran callback")).kind());
            }
        }
    }

    @Test void producerFailureRollsBackTheEntryAndNeverCallsBack() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);
            var f = fixture(db, "a".repeat(64));
            execute(db.owner, "alter table task.resource_execution_audit_source rename to unavailable_entry_audit_source");
            try (var gate = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource())) {
                assertThrows(RuntimeException.class, () -> gate.open(f.binding(), f.entry(1), UUID.randomUUID(), ignored -> {})
                        .execute(ignored -> fail("callback entered without retained audit source")));
                assertEquals(0, count(db.owner, "select count(*) from task.resource_execution"));
            }
        }
    }

    @Test void legacyEntryIsNotBackfilledOrReexecuted() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);
            var f = fixture(db, "a".repeat(64));
            try (var legacy = new JdbcResourceExecutionGate(db.owner, 1)) {
                legacy.open(f.binding(), f.entry(1), UUID.randomUUID(), ignored -> {}).execute(ignored -> "done");
            }
            try (var audited = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource(),
                    new AuditAppendOnce(db.owner)::appendOnce)) {
                assertEquals(JdbcResourceExecutionGate.Kind.RETAINED,
                        audited.open(f.binding(), f.entry(1), UUID.randomUUID(), ignored -> {}).execute(ignored -> fail("legacy retry called")).kind());
                assertTrue(audited.findEntryAudit(f.entry(1).scope()).isEmpty());
                assertThrows(IllegalStateException.class, () -> audited.deliverEntryAudit(f.entry(1).scope()));
                assertEquals(0, count(db.owner, "select count(*) from task.resource_execution_audit_source"));
            }
        }
    }

    @Test void authorityLossAfterAuditPreventsCallbackButPreservesFacts() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate(null);
            var f = fixture(db, "a".repeat(64));
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            try (var gate = new JdbcResourceExecutionGate(db.owner, null, 1,
                    JdbcResourceExecutionGate.IoBounds.defaults(), new ResourceEntryAuditSource(),
                    new AuditAppendOnce(db.owner)::appendOnce)) {
                assertThrows(IllegalStateException.class, () -> gate.open(f.binding(), f.entry(1), UUID.randomUUID(), ignored -> {
                    if (checks.incrementAndGet() == 3) throw new IllegalStateException("authority lost");
                }).execute(ignored -> fail("callback entered after authority loss")));
                assertEquals(1, count(db.owner, "select count(*) from task.resource_execution_audit_source"));
                assertEquals(1, count(db.owner, "select count(*) from audit.event where event_type='resource.execution.entered'"));
            }
        }
    }
}
