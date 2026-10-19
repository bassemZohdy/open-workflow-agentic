package io.github.bassemzohdy.openworkflow.agentic.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.DefinitionRef;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.FlowWorkflowEngine;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowEngine;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test engine: delegates to the real Quarkus Flow adapter, and counts calls. It can fail the next starts, so the
 * tests can show what the registry looks like when the engine does not confirm a start. Enabled for all boot tests
 * in this module, as {@code StubLlm} is for the model.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class CountingWorkflowEngine implements WorkflowEngine {

    @Inject
    FlowWorkflowEngine real;

    private final AtomicInteger prepares = new AtomicInteger();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger discards = new AtomicInteger();
    private final AtomicInteger failStarts = new AtomicInteger();
    private final AtomicReference<CountDownLatch[]> holdNextPrepare = new AtomicReference<>();

    @Override
    public boolean hasDefinition(DefinitionRef definition) {
        return real.hasDefinition(definition);
    }

    @Override
    public EngineLaunch prepare(DefinitionRef definition, Map<String, Object> input) {
        prepares.incrementAndGet();
        CountDownLatch[] hold = holdNextPrepare.getAndSet(null);
        if (hold != null) {
            hold[0].countDown();
            awaitQuietly(hold[1]);
        }
        EngineLaunch inner = real.prepare(definition, input);
        return new EngineLaunch() {
            @Override
            public String engineInstanceId() {
                return inner.engineInstanceId();
            }

            @Override
            public void start() {
                starts.incrementAndGet();
                if (failStarts.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    throw new IllegalStateException("simulated engine failure before the instance was accepted");
                }
                inner.start();
            }

            @Override
            public boolean discard() {
                discards.incrementAndGet();
                return inner.discard();
            }
        };
    }

    @Override
    public Optional<ActiveInstance> activeInstance(DefinitionRef definition, String engineInstanceId) {
        return real.activeInstance(definition, engineInstanceId);
    }

    @Override
    public List<String> activeInstanceIds(DefinitionRef definition) {
        return real.activeInstanceIds(definition);
    }

    @Override
    public List<DefinitionRef> definitions() {
        return real.definitions();
    }

    @Override
    public boolean startRestored(DefinitionRef definition, String engineInstanceId) {
        return real.startRestored(definition, engineInstanceId);
    }

    @Override
    public boolean cancel(DefinitionRef definition, String engineInstanceId) {
        return real.cancel(definition, engineInstanceId);
    }

    /** Resets the counters, the pending failures and any held prepare. */
    public void reset() {
        prepares.set(0);
        starts.set(0);
        discards.set(0);
        failStarts.set(0);
        holdNextPrepare.set(null);
    }

    /**
     * The next {@code prepare} counts down {@code entered} and waits for {@code release} before it prepares, so a test can
     * change the registry row while the start is between its insert and its engine id (WP-105d).
     */
    public void holdNextPrepare(CountDownLatch entered, CountDownLatch release) {
        holdNextPrepare.set(new CountDownLatch[] {entered, release});
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public int prepares() {
        return prepares.get();
    }

    public int starts() {
        return starts.get();
    }

    public int discards() {
        return discards.get();
    }

    /** The next {@code n} calls to {@link EngineLaunch#start()} fail. */
    public void failNextStarts(int n) {
        failStarts.set(n);
    }
}
