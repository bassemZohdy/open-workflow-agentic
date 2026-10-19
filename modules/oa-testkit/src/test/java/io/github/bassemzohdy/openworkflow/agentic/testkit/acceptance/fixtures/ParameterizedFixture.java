package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Parameterized tests are not supported by @Acceptance (report names would not match markers). */
public class ParameterizedFixture {
    @ParameterizedTest
    @ValueSource(strings = {"a", "b"})
    @Acceptance("T01")
    void parameterized(String value) {
    }
}
