package io.github.bassemzohdy.openworkflow.agentic.runtime.policy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The policy package stays framework-free (WP-103 notes): the enforcement point, the snapshot lifecycle and the Cedar
 * adapter depend on the contracts and on the Cedar library only, so they can be used outside Quarkus and tested as
 * plain Java.
 */
class PolicyArchitectureTest {

    static final String ROOT = "io.github.bassemzohdy.openworkflow.agentic";

    static final ArchRule POLICY_PACKAGE_IS_FRAMEWORK_FREE = noClasses()
            .that().resideInAPackage(ROOT + ".runtime.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.quarkus..", "jakarta..", "javax..", "org.eclipse.microprofile..", "io.smallrye..", "org.jboss..");

    @Test
    void policyPackageDoesNotDependOnAFramework() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT + ".runtime.policy");
        Assertions.assertTrue(classes.size() >= 10, "rule would be vacuous: only " + classes.size() + " classes");
        POLICY_PACKAGE_IS_FRAMEWORK_FREE.check(classes);
    }

    @Test
    void theRuleFailsWhenAPolicyClassUsesAFramework() {
        JavaClasses fixture = new ClassFileImporter().importPackages(ROOT + ".runtime.policy.fixture");
        Assertions.assertTrue(POLICY_PACKAGE_IS_FRAMEWORK_FREE.evaluate(fixture).hasViolation(),
                "the policy rule must detect a framework dependency");
    }
}
