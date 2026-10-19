package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.AgentTaskContract;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ExecutionLimits;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.RiskTier;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentTaskCodecTest {

    private static AgentTaskContract contract(Set<String> tools, Duration wallClock) {
        return new AgentTaskContract(
                Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"),
                "research the topic and draft a brief",
                tools,
                Set.of("scope:notes", "scope:drafts"),
                RiskTier.MEDIUM,
                new ExecutionLimits(25, wallClock, 5_000_000L),
                new Subject(SubjectKind.USER, "alice"),
                Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAW"));
    }

    @Test
    void aContractSurvivesTheRoundTrip() {
        AgentTaskContract original = contract(Set.of("tool:search", "tool:fetch"), Duration.ofMinutes(10));
        assertEquals(original, AgentTaskCodec.decode(AgentTaskCodec.encode(original)));
    }

    @Test
    void equalContractsHaveEqualTextWhateverTheOrderOfTheirSets() {
        String a = AgentTaskCodec.encode(contract(Set.of("tool:b", "tool:a", "tool:c"), Duration.ofMinutes(1)));
        String b = AgentTaskCodec.encode(contract(Set.of("tool:c", "tool:a", "tool:b"), Duration.ofMinutes(1)));
        assertEquals(a, b);
    }

    @Test
    void theWireFormUsesWholeSecondsAsTheSchemaDefines() {
        String text = AgentTaskCodec.encode(contract(Set.of(), Duration.ofSeconds(90)));
        assertTrue(text.contains("\"maxWallClockSeconds\":90"), text);
        assertTrue(text.contains("\"permittedTools\":[]"), text);
    }

    @Test
    void aWallClockWithFractionOfASecondIsRefusedRatherThanRounded() {
        AgentTaskContract fractional = contract(Set.of(), Duration.ofMillis(1500));
        assertThrows(IllegalArgumentException.class, () -> AgentTaskCodec.encode(fractional));
    }
}
