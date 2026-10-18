package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import org.junit.jupiter.api.Test;

/** T01 verifies M01, M02, M12 and M14 only, so declaring M29 must fail the test. */
public class UnverifiedRequirementFixture {
    @Test
    @Acceptance(value = "T01", requirements = {"M29"})
    void claimsARequirementTheTestDoesNotVerify() {
    }
}
