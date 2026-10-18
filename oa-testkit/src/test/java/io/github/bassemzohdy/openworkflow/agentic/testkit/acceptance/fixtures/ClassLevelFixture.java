package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.fixtures;

import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;
import org.junit.jupiter.api.Test;

/** An annotation on the class applies to each test method that has no annotation of its own. */
@Acceptance("T02")
public class ClassLevelFixture {
    @Test
    void inheritsTheClassAnnotation() {
    }
}
