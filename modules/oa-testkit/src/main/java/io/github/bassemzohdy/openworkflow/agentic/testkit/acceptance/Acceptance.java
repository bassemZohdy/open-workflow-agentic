package io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a JUnit 5 test (or a test class) as the automation of one acceptance test from
 * {@code docs/test-cases.md}.
 *
 * <p>The extension checks the id against {@code docs/requirements/requirements.json} before the test body
 * runs and fails the test when the id is unknown. {@link AcceptanceStatus} later turns passing annotated tests
 * into {@code automated} entries in {@code docs/requirements/test-status.json}. See {@code docs/testing.md}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(AcceptanceExtension.class)
public @interface Acceptance {

    /** The acceptance test id, {@code T01} to {@code T39}. */
    String value();

    /**
     * Requirement ids this test verifies (for example {@code M03}). Each must be a known requirement and must
     * be listed under the test's {@code verifies} in requirements.json. Empty means not declared.
     */
    String[] requirements() default {};
}
