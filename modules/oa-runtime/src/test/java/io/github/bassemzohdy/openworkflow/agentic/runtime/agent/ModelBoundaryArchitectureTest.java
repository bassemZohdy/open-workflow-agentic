package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Model calls go only through {@link GovernedLlmClient}: no other production class may call
 * {@link LlmClient#complete}, so no code path reaches a provider without the PEP decision and audit record.
 */
class ModelBoundaryArchitectureTest {

    static final String ROOT = "io.github.bassemzohdy.openworkflow.agentic";

    static final ArchRule ONLY_THE_GOVERNED_CLIENT_CALLS_THE_PROVIDER = noClasses()
            .that().doNotHaveFullyQualifiedName(GovernedLlmClient.class.getName())
            .should().callMethod(LlmClient.class, "complete", String.class, String.class)
            .because("a model call must be decided and audited by the PEP before the provider is reached");

    @Test
    void noProductionClassBypassesTheGovernedClient() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        Assertions.assertTrue(classes.contain(GovernedLlmClient.class), "rule would be vacuous: client not imported");
        ONLY_THE_GOVERNED_CLIENT_CALLS_THE_PROVIDER.check(classes);
    }

    @Test
    void theRuleSeesTheGovernedClientItselfCallingTheProvider() {
        // without the exemption the production call site is found, so the rule is looking at the right call
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        ArchRule withoutExemption = noClasses().should().callMethod(LlmClient.class, "complete", String.class, String.class);
        Assertions.assertTrue(withoutExemption.evaluate(classes).hasViolation());
    }

    @Test
    void theRuleFailsOnABypassingFixture() {
        JavaClasses fixture = new ClassFileImporter().importPackages(ROOT + ".runtime.agent.fixture");
        Assertions.assertTrue(ONLY_THE_GOVERNED_CLIENT_CALLS_THE_PROVIDER.evaluate(fixture).hasViolation(),
                "the rule must detect BypassingCaller");
    }
}
