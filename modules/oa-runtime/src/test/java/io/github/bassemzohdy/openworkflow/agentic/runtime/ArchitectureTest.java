package io.github.bassemzohdy.openworkflow.agentic.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Runtime never depends on control (ADR 0017, docs/plan/README.md section 2). */
class ArchitectureTest {

    static final String ROOT = "io.github.bassemzohdy.openworkflow.agentic";

    static final ArchRule RUNTIME_DOES_NOT_DEPEND_ON_CONTROL = noClasses()
            .that().resideInAPackage(ROOT + ".runtime..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".control..");

    @Test
    void runtimeDoesNotDependOnControl() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        Assertions.assertTrue(classes.stream().anyMatch(c -> c.getPackageName().startsWith(ROOT + ".runtime")),
                "rule would be vacuous: no runtime classes imported");
        RUNTIME_DOES_NOT_DEPEND_ON_CONTROL.check(classes);
    }

    @Test
    void theRuleFailsOnAViolatingFixture() {
        JavaClasses fixture = new ClassFileImporter().importPackages(ROOT + ".runtime.fixture", ROOT + ".control.fixture");
        Assertions.assertTrue(RUNTIME_DOES_NOT_DEPEND_ON_CONTROL.evaluate(fixture).hasViolation(),
                "the runtime rule must detect RuntimeLeak");
    }
}
