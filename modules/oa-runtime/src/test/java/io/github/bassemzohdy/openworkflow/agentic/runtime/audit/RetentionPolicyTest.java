package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetentionPolicyTest {

    @Test
    void theDefaultIsReferenceOnlyWithNoRulesAndNoSalt() {
        RetentionPolicy policy = RetentionPolicy.referenceOnlyByDefault();
        assertEquals(RetentionForm.STORE_REFERENCE_ONLY, policy.ruleFor("any.type").form());
        assertTrue(policy.redactionSalt().isEmpty());
    }

    @Test
    void aRuleForOneTypeDoesNotChangeTheOthers() {
        RetentionPolicy policy = RetentionPolicy.of(RetentionForm.STORE_REFERENCE_ONLY,
                Map.of("tool.call.allowed", RetentionPolicy.Rule.of(RetentionForm.STORE_INLINE)), null);
        assertEquals(RetentionForm.STORE_INLINE, policy.ruleFor("tool.call.allowed").form());
        assertEquals(RetentionForm.STORE_REFERENCE_ONLY, policy.ruleFor("model.call.allowed").form());
    }

    @Test
    void redactedPathsNeedTheRedactedForm() {
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy.Rule(RetentionForm.STORE_INLINE,
                Set.of("customer.iban")));
    }

    @Test
    void aRedactingRuleNeedsASalt() {
        Map<String, RetentionPolicy.Rule> rules = Map.of("customer.updated",
                new RetentionPolicy.Rule(RetentionForm.STORE_REDACTED, Set.of("customer.iban")));
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.of(RetentionForm.STORE_INLINE, rules, null));
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.of(RetentionForm.STORE_INLINE, rules, "  "));
        assertTrue(RetentionPolicy.of(RetentionForm.STORE_INLINE, rules, "salt").redactionSalt().isPresent());
    }

    @Test
    void aRedactingDefaultNeedsASaltToo() {
        assertThrows(IllegalArgumentException.class,
                () -> RetentionPolicy.of(RetentionForm.STORE_REDACTED, Map.of(), null));
    }

    @Test
    void blankPathsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RetentionPolicy.Rule(RetentionForm.STORE_REDACTED,
                Set.of("")));
    }

    @Test
    void formsInUseListsTheDefaultAndTheRules() {
        RetentionPolicy policy = RetentionPolicy.of(RetentionForm.STORE_REFERENCE_ONLY,
                Map.of("a", RetentionPolicy.Rule.of(RetentionForm.STORE_INLINE)), null);
        assertEquals(Set.of(RetentionForm.STORE_REFERENCE_ONLY, RetentionForm.STORE_INLINE), policy.formsInUse());
        assertFalse(policy.formsInUse().contains(RetentionForm.STORE_REDACTED));
    }
}
