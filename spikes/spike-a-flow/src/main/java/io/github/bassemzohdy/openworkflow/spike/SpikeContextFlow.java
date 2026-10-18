package io.github.bassemzohdy.openworkflow.spike;

import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import io.quarkiverse.flow.Flow;
import io.quarkus.arc.Arc;
import io.serverlessworkflow.api.types.Workflow;
import jakarta.enterprise.context.ApplicationScoped;

/** Java DSL definition that reports MDC and thread from inside a function task (Q10). */
@ApplicationScoped
public class SpikeContextFlow extends Flow {

    @Override
    public Workflow descriptor() {
        return workflow("spike-context", "spike")
                .tasks(function("readMdc",
                        (String ignored) -> Arc.container().select(GreetingService.class).get().mdcValue(),
                        String.class))
                .build();
    }
}
