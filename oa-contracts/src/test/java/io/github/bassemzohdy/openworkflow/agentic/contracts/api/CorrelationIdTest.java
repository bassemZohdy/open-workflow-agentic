package io.github.bassemzohdy.openworkflow.agentic.contracts.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CorrelationIdTest {

    @Test
    void validValuesAreAccepted() {
        assertEquals("abc-123_x.y:z", CorrelationId.parse("abc-123_x.y:z").orElseThrow().value());
        assertTrue(CorrelationId.parse("x".repeat(CorrelationId.MAX_LENGTH)).isPresent());
    }

    @Test
    void surroundingSpacesAreIgnoredButInnerSpacesAreNot() {
        assertEquals("trimmed", CorrelationId.parse("  trimmed  ").orElseThrow().value());
        assertFalse(CorrelationId.parse("bad value").isPresent());
    }

    @Test
    void unsafeOrOversizedValuesAreRejected() {
        assertFalse(CorrelationId.parse("<script>").isPresent());
        assertFalse(CorrelationId.parse("x".repeat(CorrelationId.MAX_LENGTH + 1)).isPresent());
        assertFalse(CorrelationId.parse(null).isPresent());
        assertFalse(CorrelationId.parse("").isPresent());
    }

    @Test
    void resolveKeepsAValidClientValue() {
        assertEquals("req-42", CorrelationId.resolve("req-42").value());
    }

    @Test
    void resolveReplacesAnInvalidValueWithAUlid() {
        String generated = CorrelationId.resolve("bad value").value();
        assertTrue(generated.matches("[0-7][0-9A-HJKMNP-TV-Z]{25}"), generated);
    }
}
