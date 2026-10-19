package io.github.bassemzohdy.openworkflow.agentic.contracts.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageRequestTest {

    @Test
    void missingLimitMeansTheDefault() {
        assertEquals(PageRequest.DEFAULT_LIMIT, PageRequest.of(null, null).limit());
        assertEquals(PageRequest.DEFAULT_LIMIT, PageRequest.firstPage().limit());
    }

    @Test
    void limitAboveTheMaximumIsCapped() {
        assertEquals(PageRequest.MAX_LIMIT, PageRequest.of("c", 10_000).limit());
        assertEquals(PageRequest.MAX_LIMIT, PageRequest.of("c", PageRequest.MAX_LIMIT).limit());
    }

    @Test
    void limitBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(null, 0));
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of(null, -3));
    }

    @Test
    void blankCursorBecomesTheFirstPage() {
        assertNull(PageRequest.of("   ", 10).cursor());
    }

    @Test
    void overlongCursorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PageRequest.of("x".repeat(513), 10));
    }
}
