package io.github.bassemzohdy.openworkflow.spike;

import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import io.quarkiverse.flow.Flow;
import io.quarkus.arc.Arc;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Java DSL definition (Q09). The function body looks the CDI bean up at call time.
 * Injecting the bean into the descriptor would run during build-time descriptor evaluation, so it is avoided.
 */
@ApplicationScoped
public class SpikeJavaFlow extends Flow {

    @Override
    public Workflow descriptor() {
        return workflow("spike-java", "spike")
                .tasks(function("greet",
                        (String name) -> Arc.container().select(GreetingService.class).get().greet(name),
                        String.class))
                .build();
    }
}
