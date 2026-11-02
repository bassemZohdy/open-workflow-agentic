package io.github.bassemzohdy.openworkflow.agentic.kube;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/** Experimental adapter for the pinned Flow lease extension; inactive in normal builds. */
@Decorator
@Priority(2000)
@IfBuildProperty(name = "agentic.kube.scheduler-bridge", stringValue = "true")
public abstract class LeaseSchedulerBridge implements Scheduler {
    @Inject @Delegate Scheduler delegate;
    @Inject @Any Instance<Scheduler> schedulers;

    private static boolean leaseJob(String id) {
        return id != null && (id.startsWith("flow-pool-leader-scheduler-")
                || id.startsWith("flow-pool-member-scheduler-"));
    }

    @Override public JobDefinition<?> newJob(String id) {
        var job = delegate.newJob(id);
        return leaseJob(id) ? job.setExecuteWith(Scheduled.SIMPLE) : job;
    }

    @Override public Trigger unscheduleJob(String id) {
        if (!leaseJob(id) || Scheduled.SIMPLE.equals(delegate.implementation())) return delegate.unscheduleJob(id);
        for (Scheduler scheduler : schedulers) {
            if (Scheduled.SIMPLE.equals(scheduler.implementation())) return scheduler.unscheduleJob(id);
        }
        throw new IllegalStateException("local scheduler unavailable for lease shutdown");
    }
}
