package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The retry link (ADR 0019, amendment 3): the derived idempotency key and what the request fingerprint includes. */
class RetryLinkTest {

    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final DefinitionRef DEF = new DefinitionRef("agentic/probe", "0.1.0");

    @Test
    void theRetryKeyIsDeterministicAndDependsOnTheCallAndTheFailedInstance() {
        Ulid failed = UlidGenerator.shared().next();
        Ulid other = UlidGenerator.shared().next();

        String key = InstanceService.retryKey("call-1", failed);
        assertEquals(key, InstanceService.retryKey("call-1", failed), "the same call gives the same key");
        assertNotEquals(key, InstanceService.retryKey("call-2", failed), "another call gives another key");
        assertNotEquals(key, InstanceService.retryKey("call-1", other), "another instance gives another key");
    }

    @Test
    void theRetryKeyFitsTheKeyRuleOfTheRegistry() {
        String key = InstanceService.retryKey("x".repeat(128), UlidGenerator.shared().next());
        assertTrue(key.startsWith("retry-"), key);
        assertEquals(70, key.length());
        assertTrue(key.matches("[A-Za-z0-9._:-]{1,128}"), key);
    }

    @Test
    void aRetryRequestHasADifferentFingerprintFromAPlainStartWithTheSameInput() {
        Map<String, Object> input = Map.of("mode", "ok");
        Ulid failed = UlidGenerator.shared().next();

        StartRequest plain = new StartRequest("tenant-a", ALICE, DEF, input, "key-1");
        StartRequest retry = new StartRequest("tenant-a", ALICE, DEF, input, "key-1", failed);

        assertNull(plain.retriedFrom());
        assertEquals(failed, retry.retriedFrom());
        assertNotEquals(plain.fingerprint(), retry.fingerprint());
        assertEquals(retry.fingerprint(),
                new StartRequest("tenant-a", ALICE, DEF, input, "key-1", failed).fingerprint(),
                "the same retry request is the same request");
    }

    @Test
    void aNewRowCarriesTheLinkOfItsRequestAndAPlainRowHasNone() {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        Ulid failed = UlidGenerator.shared().next();

        InstanceRecord linked = InstanceRecord.starting(UlidGenerator.shared().next(),
                new StartRequest("tenant-a", ALICE, DEF, Map.of(), "key-2", failed), now);
        InstanceRecord plain = InstanceRecord.starting(UlidGenerator.shared().next(),
                new StartRequest("tenant-a", ALICE, DEF, Map.of(), "key-3"), now);

        assertEquals(failed, linked.retriedFrom());
        assertNull(plain.retriedFrom());
        InstanceRecord next = linked.successor(now, InstanceStatus.RUNNING, "engine-1", 0, null, null);
        assertEquals(failed, next.retriedFrom(), "the link survives a change of status");
    }
}
