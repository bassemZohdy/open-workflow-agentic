package io.github.bassemzohdy.openworkflow.agentic.contracts;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Contracts depend on nothing outside java.* (and on each other). */
class ArchitectureTest {

    static final String CONTRACTS = "io.github.bassemzohdy.openworkflow.agentic.contracts";

    static final ArchRule CONTRACTS_DEPEND_ON_JDK_ONLY = classes()
            .that().resideInAPackage(CONTRACTS + "..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", CONTRACTS + "..");

    @Test
    void contractsDependOnNothingOutsideTheJdk() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(CONTRACTS);
        Assertions.assertTrue(classes.size() > 5, "rule would be vacuous: only " + classes.size() + " classes imported");
        CONTRACTS_DEPEND_ON_JDK_ONLY.check(classes);
    }

    @Test
    void theRuleFailsOnAViolatingFixture() {
        JavaClasses fixture = new ClassFileImporter().importPackages(CONTRACTS + ".fixture");
        Assertions.assertTrue(CONTRACTS_DEPEND_ON_JDK_ONLY.evaluate(fixture).hasViolation(),
                "the contracts rule must detect ContractLeak");
    }
}
