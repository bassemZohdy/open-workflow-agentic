package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import org.junit.jupiter.api.Test;

/** Run only by AcceptanceExtensionTest. T999 does not exist, so the extension must fail the test. */
public class UnknownIdFixture {
    @Test
    @Acceptance("T999")
    void claimsAnUnknownTest() {
    }
}
