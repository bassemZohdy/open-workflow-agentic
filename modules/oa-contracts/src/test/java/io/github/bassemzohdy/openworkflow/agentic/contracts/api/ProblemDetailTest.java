package io.github.bassemzohdy.openworkflow.agentic.contracts.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ProblemDetailTest {

    @Test
    void ofUsesAboutBlankAndNoInstance() {
        ProblemDetail problem = ProblemDetail.of(404, "Not Found", "no such path");
        assertEquals(URI.create("about:blank"), problem.type());
        assertEquals(404, problem.status());
        assertNull(problem.instance());
    }

    @Test
    void statusMustBeAnErrorStatus() {
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.of(200, "OK", null));
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.of(600, "Unknown", null));
    }

    @Test
    void titleMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class, () -> ProblemDetail.of(400, " ", null));
    }
}
