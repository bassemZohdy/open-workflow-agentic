package io.github.bassemzohdy.openworkflow.agentic.contracts.fixture;

import org.junit.jupiter.api.Assertions;

/**
 * Deliberately violates the contracts rule (depends on a non-JDK, non-contracts class).
 * Lives in test sources; the production rule imports with DO_NOT_INCLUDE_TESTS, so it is never seen there.
 */
public final class ContractLeak {
    public static void leak() {
        Assertions.assertTrue(true);
    }
}
