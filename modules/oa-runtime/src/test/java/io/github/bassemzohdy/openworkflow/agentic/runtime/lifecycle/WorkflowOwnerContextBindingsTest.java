package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.serverlessworkflow.impl.WorkflowContextData;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowOwnerContextBindingsTest {
    private static WorkflowContextData context() {
        return (WorkflowContextData) Proxy.newProxyInstance(WorkflowContextData.class.getClassLoader(),
                new Class<?>[]{WorkflowContextData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> true; case "hashCode" -> 1; default -> null;
                });
    }
    @Test void exactContextIdentityCannotBeReboundToSuccessor() {
        var bindings = new WorkflowOwnerContextBindings();
        var a = context(); var b = context();
        var binding = new JdbcWorkflowOwnerLease.Binding("tenant", UlidGenerator.shared().next(), "app", "engine",
                new DefinitionRef("agentic/test", "1"));
        var first = new JdbcWorkflowOwnerLease.Claim(binding, UUID.randomUUID(), 1);
        var successor = new JdbcWorkflowOwnerLease.Claim(binding, UUID.randomUUID(), 2);
        bindings.bind(a, first);
        bindings.bind(a, first);
        assertEquals(first, bindings.require(a));
        assertThrows(IllegalStateException.class, () -> bindings.require(b), "equal-looking contexts are distinct");
        assertThrows(IllegalStateException.class, () -> bindings.bind(a, successor));
        bindings.bind(b, successor);
        assertEquals(first, bindings.require(a));
        assertEquals(successor, bindings.require(b));
    }
}
