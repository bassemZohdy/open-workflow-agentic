package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.quarkiverse.flow.providers.QuarkusManagedExecutorServiceFactory;
import io.quarkiverse.flow.recorders.WorkflowApplicationBuilderCustomizer;
import io.quarkus.arc.Unremovable;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.events.EventConsumer;
import io.serverlessworkflow.impl.events.EventPublisher;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ServiceLoader;

/** Preserve explicitly configured transport beans; replace only Flow's otherwise implicit local broker. */
@Singleton
@Unremovable
public class AtomicLocalEventsCustomizer implements WorkflowApplicationBuilderCustomizer {
    @Inject Instance<EventConsumer<?, ?>> consumers;
    @Inject Instance<EventPublisher> publishers;
    @Inject QuarkusManagedExecutorServiceFactory executors;

    @Override
    public void customize(WorkflowApplication.Builder builder) {
        if (!consumers.isUnsatisfied() || !publishers.isUnsatisfied()
                || ServiceLoader.load(EventConsumer.class).stream().findAny().isPresent()
                || ServiceLoader.load(EventPublisher.class).stream().findAny().isPresent()) return;
        // Use the same managed executor factory that Flow's creator installs for the application.
        var broker = new AtomicInMemoryEvents(executors);
        builder.withEventConsumer(broker).withEventPublisher(broker);
    }
}
