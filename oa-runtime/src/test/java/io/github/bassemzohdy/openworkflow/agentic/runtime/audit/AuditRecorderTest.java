package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.Decision;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.DecisionOutcome;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.PolicyRef;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Retention forms, version stamps and the chain (WP-111 deliverables 4 and 5). */
class AuditRecorderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T09:00:00Z"), ZoneOffset.UTC);
    private static final Subject AGENT = new Subject(SubjectKind.AGENT, "research-agent");

    private final AuditTrail trail = new AuditTrail(AuditTestDatabase.dataSource());
    private final JdbcPayloadStore payloads = new JdbcPayloadStore(AuditTestDatabase.dataSource());

    @Test
    void inlineKeepsEveryFieldInTheEventAndTheChainVerifies() throws SQLException {
        AuditRecorder recorder = recorder(policy(RetentionForm.STORE_INLINE, Map.of(), null));
        String chain = AuditTestDatabase.newChain();
        AuditEvent event = recorder.record(request("tool.call.allowed", chain)
                .withPayload(Map.of("query", "agentic workflows", "limit", "5")));

        assertEquals("agentic workflows", event.attributes().get("field.query"));
        assertEquals("STORE_INLINE", event.attributes().get(AuditKeys.PAYLOAD_FORM));
        assertFalse(event.attributes().containsKey(AuditKeys.PAYLOAD_REF));
        assertEquals(AuditRecorder.PayloadStatus.NOT_REFERENCED, recorder.payloadStatus(event));
        assertIntact(chain);
    }

    @Test
    void redactedFieldIsASaltedDigestAndTheOtherFieldsStayReadable() throws SQLException {
        RetentionPolicy policy = policy(RetentionForm.STORE_INLINE, Map.of("customer.updated",
                new RetentionPolicy.Rule(RetentionForm.STORE_REDACTED, Set.of("customer.iban"))), "salt-one");
        AuditRecorder recorder = recorder(policy);
        String chain = AuditTestDatabase.newChain();
        AuditEvent event = recorder.record(request("customer.updated", chain)
                .withPayload(Map.of("customer.iban", "AE070331234567890123456", "channel", "portal")));

        String redacted = event.attributes().get("field.customer.iban");
        assertTrue(redacted.startsWith(PayloadProtection.REDACTED_PREFIX), redacted);
        assertFalse(redacted.contains("AE0703"));
        assertEquals("portal", event.attributes().get("field.channel"));
        assertIntact(chain);

        String otherSalt = PayloadProtection.redact("salt-two", "customer.iban", "AE070331234567890123456");
        assertNotEquals(redacted, otherSalt, "the salt must change the digest");
    }

    @Test
    void referenceOnlyKeepsADigestAndTheChainSurvivesDeletingThePayload() throws SQLException {
        AuditRecorder recorder = recorder(RetentionPolicy.referenceOnlyByDefault());
        String chain = AuditTestDatabase.newChain();
        AuditEvent event = recorder.record(request("model.call.allowed", chain)
                .withPayload(Map.of("prompt", "summarise the claim file", "reply", "a long reply")));

        assertEquals("STORE_REFERENCE_ONLY", event.attributes().get(AuditKeys.PAYLOAD_FORM));
        String ref = event.attributes().get(AuditKeys.PAYLOAD_REF);
        assertTrue(payloads.get(ref).isPresent());
        assertEquals(AuditRecorder.PayloadStatus.MATCHES, recorder.payloadStatus(event));
        assertFalse(event.attributes().keySet().stream().anyMatch(k -> k.startsWith(AuditKeys.FIELD_PREFIX)),
                "the event must not carry the payload fields");

        payloads.delete(ref);

        assertTrue(payloads.get(ref).isEmpty());
        assertEquals(AuditRecorder.PayloadStatus.DELETED, recorder.payloadStatus(event));
        assertIntact(chain);
    }

    @Test
    void aStoredPayloadThatNoLongerMatchesItsDigestIsReported() throws SQLException {
        AuditRecorder recorder = recorder(RetentionPolicy.referenceOnlyByDefault());
        String chain = AuditTestDatabase.newChain();
        AuditEvent event = recorder.record(request("model.call.allowed", chain)
                .withPayload(Map.of("prompt", "original")));
        String ref = event.attributes().get(AuditKeys.PAYLOAD_REF);

        payloads.delete(ref);
        payloads.put(ref, Map.of("prompt", "replaced"));

        assertEquals(AuditRecorder.PayloadStatus.DOES_NOT_MATCH, recorder.payloadStatus(event));
        assertIntact(chain);
    }

    @Test
    void versionStampsAreHashedWithTheEvent() throws SQLException {
        AuditRecorder recorder = recorder(RetentionPolicy.referenceOnlyByDefault());
        String chain = AuditTestDatabase.newChain();
        VersionStamps stamps = new VersionStamps("gpt-4o-mini@2026-10-01", "research-v3", "search-tool@2", "cedar.main",
                "2026-10-01", "research-and-approve@7");
        AuditEvent event = recorder.record(request("decision.allowed", chain).withStamps(stamps));

        assertEquals("gpt-4o-mini@2026-10-01", event.attributes().get("stamp.model"));
        assertEquals("research-v3", event.attributes().get("stamp.prompt"));
        assertEquals("search-tool@2", event.attributes().get("stamp.tool"));
        assertEquals("cedar.main", event.attributes().get("stamp.policy"));
        assertEquals("2026-10-01", event.attributes().get("stamp.policy-set"));
        assertEquals("research-and-approve@7", event.attributes().get("stamp.definition"));
        assertIntact(chain);
    }

    @Test
    void absentStampsAreNotWritten() {
        AuditRecorder recorder = recorder(RetentionPolicy.referenceOnlyByDefault());
        AuditEvent event = recorder.record(request("step.started", AuditTestDatabase.newChain()));
        assertFalse(event.attributes().keySet().stream().anyMatch(k -> k.startsWith(AuditKeys.STAMP_PREFIX)));
    }

    @Test
    void policyStampsComeFromTheDecisionRecord() {
        Decision decision = new Decision("cedar-embedded", DecisionOutcome.ALLOW, "tool in contract",
                new PolicyRef("tool.search.allow", "2026-10-02"), Instant.parse("2026-10-18T08:59:00Z"));
        VersionStamps stamps = VersionStamps.ofDecision(decision);
        assertEquals("tool.search.allow", stamps.policy());
        assertEquals("2026-10-02", stamps.policySet());
        assertNull(stamps.model());
    }

    @Test
    void theEnvelopeCarriesChainTenantInstanceAndCorrelation() throws SQLException {
        AuditRecorder recorder = recorder(RetentionPolicy.referenceOnlyByDefault());
        String chain = AuditTestDatabase.newChain();
        AuditEvent event = recorder.record(request("step.started", chain).forTenant("dubai-dev")
                .forInstance("wf-instance-42").withCorrelation("req-abc"));

        assertEquals("dubai-dev", event.attributes().get(AuditKeys.TENANT));
        assertEquals("wf-instance-42", event.attributes().get(AuditKeys.INSTANCE));
        assertEquals("req-abc", event.attributes().get(AuditKeys.CORRELATION));
        assertIntact(chain);
    }

    @Test
    void aTamperedInlineFieldIsCaughtByTheVerifier() throws SQLException {
        AuditRecorder recorder = recorder(policy(RetentionForm.STORE_INLINE, Map.of(), null));
        String chain = AuditTestDatabase.newChain();
        recorder.record(request("tool.call.allowed", chain).withPayload(Map.of("query", "safe")));
        Tamper.run("update audit.event set attributes = jsonb_set(attributes, '{field.query}', '\"forged\"') "
                + "where chain_id = '" + chain + "'");

        ChainReport report = new ChainVerifier(AuditTestDatabase.dataSource()).verify(chain);
        assertFalse(report.intact());
        assertEquals(1L, report.firstBrokenSequence());
    }

    private AuditRecorder recorder(RetentionPolicy policy) {
        return new AuditRecorder(trail, payloads, policy, CLOCK);
    }

    private static RetentionPolicy policy(RetentionForm defaultForm, Map<String, RetentionPolicy.Rule> rules,
                                          String salt) {
        return RetentionPolicy.of(defaultForm, rules, salt);
    }

    private static AuditRequest request(String action, String chain) {
        return AuditRequest.of(AGENT, action, "run", "run-1").inChain(chain);
    }

    private void assertIntact(String chain) throws SQLException {
        List<AuditEvent> events = AuditTrailTest.readChain(chain);
        assertTrue(AuditHash.verifyChain(events).valid());
        ChainReport report = new ChainVerifier(AuditTestDatabase.dataSource()).verify(chain);
        assertTrue(report.intact(), report.reason());
    }
}
