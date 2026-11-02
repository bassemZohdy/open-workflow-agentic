package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerAuthority;
import io.github.bassemzohdy.openworkflow.agentic.runtime.api.caller.CallerContext;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.AuthorityLostException;
import io.github.bassemzohdy.openworkflow.agentic.runtime.identity.VerifiedToken;
import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HumanDecisionAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Ulid TASK = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final Ulid ID = Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW");
    private static final Subject ALICE = new Subject(SubjectKind.USER, "alice");
    private static final Subject BOB = new Subject(SubjectKind.USER, "bob");
    private static final Set<String> SCOPES = Set.of("human-task:decide");

    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final PolicyDoubles.InMemoryAuditSink audit = new PolicyDoubles.InMemoryAuditSink();

    private HumanActionFacts facts(Subject initiator, Instant due) {
        return new HumanActionFacts("tenant", TASK, 3, HumanTaskStatus.IN_PROGRESS, due, null, initiator, ALICE,
                RiskTier.HIGH, HumanActionFacts.SeparationObligation.INITIATOR_MUST_NOT_ACT);
    }
    private HumanDecision request(Subject actor, long version) {
        return new HumanDecision(ID, "tenant", TASK, version, actor, HumanDecision.Outcome.APPROVE, "private");
    }
    private CallerAuthority authority(List<String> actors, Instant expiry) {
        var token = new VerifiedToken("issuer", ALICE, Set.of("api"), SCOPES, "tenant", NOW, expiry, "id", actors);
        return actors.isEmpty() ? CallerAuthority.ofUserToken(token, clock)
                : CallerAuthority.ofDelegatedToken(token, clock, (ignored, scopes) -> { });
    }
    private HumanDecisionAdmission admission(PolicyDoubles.ScriptedEngine engine) {
        return new HumanDecisionAdmission(new Pep(engine, audit, Duration.ofSeconds(2), clock), clock);
    }
    private Decision allow() { return new Decision("test", DecisionOutcome.ALLOW, "ok", new PolicyRef("p", "1"), NOW); }

    @Test void verifiedAssigneePassesButForbiddenInitiatorIsDenied() {
        var engine = new PolicyDoubles.ScriptedEngine(r -> allow());
        var admission = admission(engine);
        var user = authority(List.of(), NOW.plusSeconds(60));
        assertTrue(admission.check(user, request(ALICE, 3), facts(BOB, null)).allows());
        assertFalse(admission.check(user, request(ALICE, 3), facts(ALICE, null)).allows());
        assertEquals(1, engine.received().size(), "the deterministic denial never reaches the policy engine");
    }

    @Test void forgedUnverifiedDelegatedAndStaleInputsNeverReachThePolicyEngine() {
        var engine = new PolicyDoubles.ScriptedEngine(r -> allow());
        var admission = admission(engine);
        var user = authority(List.of(), NOW.plusSeconds(60));
        assertThrows(SecurityException.class, () -> admission.check(user, request(BOB, 3), facts(BOB, null)));
        assertThrows(SecurityException.class, () -> admission.check(user, request(ALICE, 4), facts(BOB, null)));
        assertThrows(SecurityException.class, () -> admission.check(authority(List.of("agent"), NOW.plusSeconds(60)),
                request(ALICE, 3), facts(BOB, null)));
        var unverified = CallerAuthority.unverified(new CallerContext("tenant", ALICE, SCOPES), clock);
        assertThrows(AuthorityLostException.class, () -> admission.check(unverified, request(ALICE, 3), facts(BOB, null)));
        assertTrue(engine.received().isEmpty());
    }

    @Test void authorityOrTaskExpiryDuringPolicyCannotReturnAnAllow() {
        var expiringUser = authority(List.of(), NOW.plusSeconds(1));
        var engine = new PolicyDoubles.ScriptedEngine(r -> { now.set(NOW.plusSeconds(2)); return allow(); });
        var admission = admission(engine);
        assertEquals(AuthorityLostException.Reason.EXPIRED, assertThrows(AuthorityLostException.class,
                () -> admission.check(expiringUser, request(ALICE, 3), facts(BOB, null))).reason());
        now.set(NOW);
        var longLived = authority(List.of(), NOW.plusSeconds(60));
        assertThrows(SecurityException.class,
                () -> admission.check(longLived, request(ALICE, 3), facts(BOB, NOW.plusSeconds(1))));
    }
}
