package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import io.cloudevents.CloudEvent;
import io.serverlessworkflow.impl.ExecutorServiceFactory;
import io.serverlessworkflow.impl.TaskContext;
import io.serverlessworkflow.impl.WorkflowContext;
import io.serverlessworkflow.impl.events.InMemoryEvents;
import io.serverlessworkflow.impl.events.TypeEventRegistration;
import io.serverlessworkflow.impl.events.TypeEventRegistrationBuilder;
import java.util.function.Consumer;

/**
 * Default local Flow broker. The pinned SDK adds to a type bucket after releasing its map computation;
 * concurrent removal of the last listener can detach that bucket before the add, losing the new listener.
 * Serialize whole registration mutations, including the SDK's bucket add/removal. Publishing and workflow
 * callbacks run outside this monitor. This remains an in-memory broadcast, not the durable governed inbox.
 * The Flow customizer selects it only when no explicit consumer or publisher bean is configured.
 */
public class AtomicInMemoryEvents extends InMemoryEvents {
    public AtomicInMemoryEvents() { super(); }

    public AtomicInMemoryEvents(ExecutorServiceFactory executors) { super(executors); }

    @Override
    public synchronized TypeEventRegistration register(TypeEventRegistrationBuilder builder,
            Consumer<CloudEvent> consumer, WorkflowContext workflow, TaskContext task) {
        return super.register(builder, consumer, workflow, task);
    }

    @Override
    public synchronized void unregister(TypeEventRegistration registration) {
        super.unregister(registration);
    }
}
