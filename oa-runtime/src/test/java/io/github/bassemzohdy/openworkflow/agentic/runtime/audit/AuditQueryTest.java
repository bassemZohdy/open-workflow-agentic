package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.api.PageRequest;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuditQueryTest {

    private final AuditTrail trail = new AuditTrail(AuditTestDatabase.dataSource());
    private final AuditQuery query = new AuditQuery(AuditTestDatabase.dataSource());

    @Test
    void filtersByInstanceAndType() {
        String chain = AuditTestDatabase.newChain();
        String instance = "wf-" + AuditTestDatabase.newId().value();
        append(chain, instance, "tool.call.allowed", "2026-10-18T10:00:00Z");
        append(chain, instance, "tool.call.denied", "2026-10-18T10:01:00Z");
        append(chain, "other-" + AuditTestDatabase.newId().value(), "tool.call.allowed", "2026-10-18T10:02:00Z");

        AuditQuery.Page page = query.find(new AuditQuery.Filter(instance, null, null, null, null, null),
                PageRequest.firstPage());
        assertEquals(2, page.items().size());
        assertNull(page.nextCursor());

        AuditQuery.Page denied = query.find(new AuditQuery.Filter(instance, null, null, "tool.call.denied", null, null),
                PageRequest.firstPage());
        assertEquals(1, denied.items().size());
        assertEquals("tool.call.denied", denied.items().get(0).action());
    }

    @Test
    void filtersByTimeRangeWithAnInclusiveStartAndExclusiveEnd() {
        String chain = AuditTestDatabase.newChain();
        String instance = "wf-" + AuditTestDatabase.newId().value();
        append(chain, instance, "a", "2026-10-18T10:00:00Z");
        append(chain, instance, "b", "2026-10-18T11:00:00Z");
        append(chain, instance, "c", "2026-10-18T12:00:00Z");

        AuditQuery.Page page = query.find(new AuditQuery.Filter(instance, null, null, null,
                Instant.parse("2026-10-18T10:00:00Z"), Instant.parse("2026-10-18T12:00:00Z")), PageRequest.firstPage());
        assertEquals(List.of("a", "b"), page.items().stream().map(AuditEvent::action).toList());
    }

    @Test
    void cursorPagesThroughAChainWithoutGapsOrRepeats() {
        String chain = AuditTestDatabase.newChain();
        String instance = "wf-" + AuditTestDatabase.newId().value();
        for (int i = 1; i <= 7; i++) {
            append(chain, instance, "step." + i, "2026-10-18T10:00:00Z");
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            AuditQuery.Page page = query.find(new AuditQuery.Filter(instance, null, null, null, null, null),
                    new PageRequest(cursor, 3));
            page.items().forEach(event -> seen.add(event.sequence()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertEquals(3, pages);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), seen);
        assertEquals(7, new HashSet<>(seen).size());
    }

    @Test
    void aMalformedCursorIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> query.find(AuditQuery.Filter.NONE,
                new PageRequest("not-a-cursor!", 10)));
        assertThrows(IllegalArgumentException.class, () -> query.find(AuditQuery.Filter.NONE,
                new PageRequest(AuditQuery.Cursor.of("chain", 0).encode(), 10)));
    }

    @Test
    void anEventIsFoundById() {
        String chain = AuditTestDatabase.newChain();
        AuditEvent stored = append(chain, null, "decision.allowed", "2026-10-18T10:00:00Z");

        Optional<AuditEvent> found = query.byId(stored.id());
        assertTrue(found.isPresent());
        assertEquals(stored.hash(), found.get().hash());
        assertFalse(query.byId(AuditTestDatabase.newId()).isPresent());
    }

    @Test
    void theCursorRoundTrips() {
        String cursor = AuditQuery.Cursor.of("chain.with:colons-1", 42).encode();
        AuditQuery.Cursor decoded = AuditQuery.Cursor.decode(cursor);
        assertEquals("chain.with:colons-1", decoded.chainId());
        assertEquals(42L, decoded.sequence());
    }

    private AuditEvent append(String chain, String instance, String action, String at) {
        Map<String, String> attributes = new java.util.LinkedHashMap<>(AuditTestDatabase.attributes(chain, Map.of()));
        if (instance != null) {
            attributes.put(AuditKeys.INSTANCE, instance);
        }
        Subject actor = new Subject(SubjectKind.SERVICE, "audit-test");
        Ulid id = AuditTestDatabase.newId();
        return trail.append(AuditDraft.of(id, Instant.parse(at), actor, action, "run", "r-1", attributes));
    }
}
