package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import org.junit.jupiter.api.Test;

/** Run only by AcceptanceExtensionTest through EngineTestKit. The name does not match surefire's includes. */
@Acceptance(value = "T01", requirements = {"M02"})
public class ValidFixture {
    @Test
    void passes() {
    }
}
