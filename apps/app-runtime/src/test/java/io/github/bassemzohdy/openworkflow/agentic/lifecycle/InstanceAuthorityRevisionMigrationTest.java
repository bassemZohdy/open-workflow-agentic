package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.InstanceAuthorityStore;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InstanceAuthorityRevisionMigrationTest {
    @Test void upgradePreservesOldRowsAndTracksUpdatesFromOlderReplicas() throws Exception {
        try (var db = new ResourceExecutionMigrationTest.Database()) {
            db.migrate("77.5");
            var store = new InstanceAuthorityStore(db.owner);
            var id = UlidGenerator.shared().next();
            var grant = UlidGenerator.shared().next();
            var now = Instant.parse("2026-11-01T00:00:00Z");
            var authority = new InstanceAuthority(id, "tenant", new Subject(SubjectKind.USER, "alice"), grant,
                    "correlation", InstanceAuthority.State.AUTHORIZED, null, now, now);
            try (var connection = db.owner.getConnection()) { store.insert(connection, authority); }

            db.migrate(null);
            var upgraded = store.find(id).orElseThrow();
            assertEquals(authority, upgraded);
            try (var connection = db.owner.getConnection(); var update = connection.prepareStatement("""
                    update identity.instance_authority set state='REAUTHORIZATION_REQUIRED', state_reason='legacy',
                        state_since=?, updated_at=? where instance_id=? and state='AUTHORIZED'
                    """)) {
                update.setTimestamp(1, java.sql.Timestamp.from(now));
                update.setTimestamp(2, java.sql.Timestamp.from(now));
                update.setString(3, id.value());
                assertEquals(1, update.executeUpdate());
            }
            var updated = store.find(id).orElseThrow();
            assertEquals(1, updated.revision());
            assertFalse(store.rebind(id, grant, upgraded.revision(), grant, now));
            assertTrue(store.rebind(id, grant, updated.revision(), grant, now));
            assertEquals(2, store.find(id).orElseThrow().revision());

            var laterId = UlidGenerator.shared().next();
            try (var connection = db.owner.getConnection()) {
                store.insert(connection, new InstanceAuthority(laterId, "tenant", authority.subject(), grant,
                        null, InstanceAuthority.State.AUTHORIZED, null, now, now));
            }
            assertEquals(0, store.find(laterId).orElseThrow().revision());
        }
    }
}
