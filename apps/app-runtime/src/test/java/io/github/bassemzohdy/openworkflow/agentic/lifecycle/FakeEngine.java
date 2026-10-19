package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.EngineStatus;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowEngine;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An engine that holds instances in a map and reports the statuses the test sets (WP-105c tests). It is not the
 * engine: Flow's behaviour is tested with the real engine in {@code FlowStatusSyncTest} and the process tests.
 */
final class FakeEngine implements WorkflowEngine {

    static final DefinitionRef PROBE = new DefinitionRef("agentic/lifecycle-probe", "0.1.0");

    private final Map<String, EngineStatus> active = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    final AtomicInteger prepares = new AtomicInteger();
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger restarts = new AtomicInteger();
    final AtomicInteger cancels = new AtomicInteger();
    volatile RuntimeException failNextStart;
    volatile CountDownLatch startEntered;
    volatile CountDownLatch startRelease;
    /** Runs inside {@code prepare}, after the engine instance exists and before it is returned (a race test hook). */
    volatile Runnable duringPrepare;

    @Override
    public boolean hasDefinition(DefinitionRef definition) {
        return PROBE.equals(definition);
    }

    @Override
    public EngineLaunch prepare(DefinitionRef definition, Map<String, Object> input) {
        prepares.incrementAndGet();
        String id = "fake-" + ids.incrementAndGet() + "-" + System.nanoTime();
        active.put(id, EngineStatus.PENDING);
        Runnable hook = duringPrepare;
        if (hook != null) {
            hook.run();
        }
        return new EngineLaunch() {
            @Override
            public String engineInstanceId() {
                return id;
            }

            @Override
            public void start() {
                starts.incrementAndGet();
                CountDownLatch entered = startEntered;
                if (entered != null) {
                    entered.countDown();
                    awaitRelease();
                }
                RuntimeException failure = failNextStart;
                if (failure != null) {
                    failNextStart = null;
                    throw failure;
                }
                active.put(id, EngineStatus.RUNNING);
            }
        };
    }

    private void awaitRelease() {
        CountDownLatch release = startRelease;
        try {
            if (release != null) {
                release.await();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Optional<ActiveInstance> activeInstance(DefinitionRef definition, String engineInstanceId) {
        EngineStatus status = active.get(engineInstanceId);
        return status == null ? Optional.empty() : Optional.of(new ActiveInstance(engineInstanceId, status));
    }

    @Override
    public List<String> activeInstanceIds(DefinitionRef definition) {
        return List.copyOf(active.keySet());
    }

    @Override
    public List<DefinitionRef> definitions() {
        return List.of(PROBE);
    }

    @Override
    public boolean startRestored(DefinitionRef definition, String engineInstanceId) {
        if (active.get(engineInstanceId) != EngineStatus.PENDING) {
            return false;
        }
        restarts.incrementAndGet();
        active.put(engineInstanceId, EngineStatus.RUNNING);
        return true;
    }

    @Override
    public boolean cancel(DefinitionRef definition, String engineInstanceId) {
        if (!active.containsKey(engineInstanceId)) {
            return false;
        }
        cancels.incrementAndGet();
        return true;
    }

    /** Sets the status the engine reports for an instance it holds. */
    void report(String engineInstanceId, EngineStatus status) {
        active.put(engineInstanceId, status);
    }

    /** The engine forgets an instance (it finished, or it was cancelled and removed). */
    void forget(String engineInstanceId) {
        active.remove(engineInstanceId);
    }

    /** Makes an instance known to the engine without the registry (an engine instance nobody registered). */
    String adopt(EngineStatus status) {
        String id = "stray-" + ids.incrementAndGet() + "-" + System.nanoTime();
        active.put(id, status);
        return id;
    }
}
