package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcResourceExecutionGate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.*;

import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.lang.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.agent.ResourceExecutionEntry;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.ResourceCallBinding;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;

/** A future composition root needs review; generic runtime code cannot construct a gate/fence or use legacy entry. */
class ResourceExecutionConstructionArchitectureTest {
    private static final String PREFIX="io.github.bassemzohdy.openworkflow.agentic";
    private static final String GATE=JdbcResourceExecutionGate.class.getName();
    private static final String ROOT=PREFIX+".runtime.execution.GovernedExecutionRoot";
    private static ArchRule rule(Set<String> roots) {
        return classes().that().resideInAPackage(PREFIX+"..")
                .should(new ArchCondition<JavaClass>("construct/use the execution gate only at the exact reviewed root") {
                    @Override public void check(JavaClass item,ConditionEvents events) {
                        if(item.getName().equals(GATE)||item.getName().startsWith(GATE+"$"))return;
                        boolean root=roots.contains(item.getName());
                        for(var call:item.getConstructorCallsFromSelf())
                            if(call.getTarget().getOwner().getName().equals(GATE)&&!root)
                                events.add(SimpleConditionEvent.violated(item,call.getDescription()));
                        for(var call:item.getMethodCallsFromSelf()) {
                            String owner=call.getTarget().getOwner().getName(),name=call.getTarget().getName();
                            boolean legacy=(owner.equals(GATE)&&name.equals("open"))
                                    ||(owner.equals(GATE+"$Attempt")&&name.equals("execute"));
                            boolean controlled=(owner.equals(GATE)&&name.equals("openGoverned"))
                                    ||(owner.equals(GATE+"$CredentialFence")&&name.equals("pair"))
                                    ||(owner.equals(GATE+"$Attempt")&&name.equals("executeGoverned"));
                            if(legacy||(controlled&&!root))events.add(SimpleConditionEvent.violated(item,call.getDescription()));
                        }
                    }
                });
    }
    @Test void productionHasNoUnreviewedConstructionOrLegacyEntryPath() {
        JavaClasses imported=new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(PREFIX);
        assertTrue(imported.stream().anyMatch(c->c.getName().equals(GATE)),"do not pass on an empty import");
        assertTrue(imported.stream().anyMatch(c->c.getName().equals(PREFIX+".lifecycle.CheckpointReadiness")),"application classes must be imported too");
        rule(Set.of(ROOT)).check(imported);
    }
    @TestFactory Stream<DynamicTest> eachIndependentBypassFixtureIsRejected() {
        return Stream.of(ConstructorLeak.class,LegacyEntryLeak.class,LegacyCallbackLeak.class,FenceLeak.class,GovernedExecutionRoot.class)
                .map(type->DynamicTest.dynamicTest(type.getSimpleName(),()->{
                    var imported=new ClassFileImporter().importClasses(type);
                    assertTrue(rule(Set.of(ROOT)).evaluate(imported).hasViolation(),"missing guard for "+type.getName());
                }));
    }
    @Test void anAllowlistEntryMatchesTheExactOwnerAndDoesNotAuthorizeLegacyEntry() {
        var ctor=new ClassFileImporter().importClasses(ConstructorLeak.class);
        assertFalse(rule(Set.of(ConstructorLeak.class.getName())).evaluate(ctor).hasViolation());
        var legacy=new ClassFileImporter().importClasses(LegacyEntryLeak.class);
        assertTrue(rule(Set.of(LegacyEntryLeak.class.getName())).evaluate(legacy).hasViolation());
    }
    static class ConstructorLeak {
        static Object make(DataSource source){return new JdbcResourceExecutionGate(source,1);}
    }
    static class LegacyEntryLeak {
        static Object enter(JdbcResourceExecutionGate gate,ResourceCallBinding b,ResourceExecutionEntry e) {
            return gate.open(b,e,UUID.randomUUID(),ignored->{ });
        }
    }
    static class LegacyCallbackLeak {
        static Object run(JdbcResourceExecutionGate.Attempt attempt){return attempt.execute(entry->null);}
    }
    static class FenceLeak {
        static Object make(ResourceCallBinding b){return JdbcResourceExecutionGate.CredentialFence.pair(b,ignored->{ });}
    }
    // Same simple name is insufficient: only the exact reviewed production package can be allowed.
    static class GovernedExecutionRoot {
        static Object make(DataSource source){return new JdbcResourceExecutionGate(source,1);}
    }
}
