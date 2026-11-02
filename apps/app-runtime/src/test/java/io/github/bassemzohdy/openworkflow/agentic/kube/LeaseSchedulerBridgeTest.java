package io.github.bassemzohdy.openworkflow.agentic.kube;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LeaseSchedulerBridgeTest {
    private static final class Harness extends LeaseSchedulerBridge {
        public boolean isStarted() { return false; }
        public void pause() { }
        public void pause(String id) { }
        public void resume() { }
        public void resume(String id) { }
        public boolean isPaused(String id) { return false; }
        public boolean isRunning() { return false; }
        public List<Trigger> getScheduledJobs() { return List.of(); }
        public Trigger getScheduledJob(String id) { return null; }
        public String implementation() { return "test"; }
    }

    @Test void onlyFlowLeaseJobsUseTheLocalSchedulerAndItsShutdownPath() {
        var chosen = new AtomicReference<String>(); var removed = new AtomicReference<String>();
        var job = (Scheduler.JobDefinition<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Scheduler.JobDefinition.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setExecuteWith")) chosen.set((String) args[0]);
                    return proxy;
                });
        Scheduler composite = scheduler("composite", job, removed);
        Scheduler simple = scheduler(Scheduled.SIMPLE, job, removed);
        var bridge = new Harness(); bridge.delegate = composite;
        @SuppressWarnings("unchecked") Instance<Scheduler> instances = (Instance<Scheduler>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instance.class},
                (proxy, method, args) -> method.getName().equals("iterator") ? List.of(composite, simple).iterator() : null);
        bridge.schedulers = instances;
        bridge.newJob("flow-pool-member-scheduler-example-pod");
        assertEquals(Scheduled.SIMPLE, chosen.get());
        chosen.set(null); bridge.newJob("business-job"); assertNull(chosen.get());
        bridge.unscheduleJob("flow-pool-leader-scheduler-example");
        assertEquals(Scheduled.SIMPLE + ":flow-pool-leader-scheduler-example", removed.get());
        bridge.unscheduleJob("business-job"); assertEquals("composite:business-job", removed.get());
    }

    private static Scheduler scheduler(String name, Scheduler.JobDefinition<?> job, AtomicReference<String> removed) {
        return (Scheduler) Proxy.newProxyInstance(LeaseSchedulerBridgeTest.class.getClassLoader(),
                new Class<?>[]{Scheduler.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "implementation" -> name;
                    case "newJob" -> job;
                    case "unscheduleJob" -> { removed.set(name + ":" + args[0]); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
