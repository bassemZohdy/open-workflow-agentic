package io.github.bassemzohdy.openworkflow.spikec;

import static io.quarkiverse.flow.dsl.FlowDSL.consumed;
import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowDSL.listen;
import static io.quarkiverse.flow.dsl.FlowDSL.toOne;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import io.quarkiverse.flow.Flow;
import io.quarkus.arc.Arc;
import io.serverlessworkflow.api.types.Workflow;

/**
 * Bridge B definition. The Java DSL is used because YAML {@code call: Java} cannot reach a CDI bean by name
 * (WP-003 Q09). The approval listen filters on the instance id carried in the task input (dataFields).
 */
@jakarta.enterprise.context.ApplicationScoped
public class InProcessFlow extends Flow {

    static final String APPROVAL = "spikec.approval.v1";

    @Override
    public Workflow descriptor() {
        return workflow("bridge-b", "spikec", "1.0.0")
                .tasks(
                        function("runAgent",
                                (Ctx in) -> Arc.container().select(InProcessBridge.class).get().run(in),
                                Ctx.class),
                        listen("awaitApproval", toOne(consumed(APPROVAL).dataFields("instanceId"))),
                        function("resumeAgent",
                                (InProcessBridge.Resume in) -> Arc.container().select(InProcessBridge.class).get()
                                        .resume(in),
                                InProcessBridge.Resume.class))
                .build();
    }
}
