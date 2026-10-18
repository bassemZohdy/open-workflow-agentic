package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import org.junit.jupiter.api.Test;

/** M99 is not a requirement, so the test must fail. */
public class UnknownRequirementFixture {
    @Test
    @Acceptance(value = "T01", requirements = {"M99"})
    void claimsAnUnknownRequirement() {
    }
}
