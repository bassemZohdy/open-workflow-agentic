package io.github.bassemzohdy.openworkflow.spikec;

import io.quarkus.runtime.StartupEvent;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup and recovery (ADR 0019, "resume after restart"). Quarkus Flow restores waiting instances but does not start
 * them (WP-003 fact 1), so this reconciler calls start() on every restored instance that the registry says was running,
 * and then restarts the bridge C agent runs that a kill -9 interrupted.
 *
 * <p>Readiness (issue #9): the API reports ready only after an attempt completes. Recovery is retried a bounded number
 * of times: {@code spikec.reconcile.max-attempts} attempts (default 3), {@code spikec.reconcile.retry-ms} apart (default
 * 2000). A retry skips the instances and runs an earlier attempt already recovered, so nothing is started twice. When
 * every attempt fails, readiness stays false and no further retry runs; a new instance is refused with a problem
 * response until a restart recovers the application.
 *
 * <p>Set {@code spikec.reconcile.enabled=false} to see the engine's behaviour without it (control test).
 * {@code spikec.reconcile.fail-attempts} forces the first attempts to fail (tests only; zero by default).
 */
@ApplicationScoped
public class Lifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(Lifecycle.class);

    @Inject
    Store store;

    @Inject
    WorkflowApplication application;

    @Inject
    AsyncAgent async;

    @ConfigProperty(name = "spikec.reconcile.enabled", defaultValue = "true")
    boolean reconcileEnabled;

    @ConfigProperty(name = "spikec.reconcile.max-attempts", defaultValue = "3")
    int maxAttempts;

    @ConfigProperty(name = "spikec.reconcile.retry-ms", defaultValue = "2000")
    long retryMs;

    /** Test-only: the first this-many attempts fail before they do any work. Zero in production. */
    @ConfigProperty(name = "spikec.reconcile.fail-attempts", defaultValue = "0")
    int forcedFailAttempts;

    /** Work done by earlier attempts, so a retry does not start an instance or a run twice. */
    private final Set<String> restoredEngineIds = ConcurrentHashMap.newKeySet();
    private final Set<String> restartedThreads = ConcurrentHashMap.newKeySet();

    private volatile boolean ready;

    public boolean isReady() {
        return ready;
    }

    void onStart(@Observes StartupEvent event) {
        store.init();
        if (!reconcileEnabled) {
            ready = true;
            LOG.info("reconciler disabled; ready");
            return;
        }
        Thread t = new Thread(this::reconcile, "spikec-reconcile");
        t.setDaemon(true);
        t.start();
    }

    private void reconcile() {
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                recoverOnce(attempt);
                ready = true;
                LOG.info("reconciliation complete on attempt {} of {}; ready", attempt, attempts);
                return;
            } catch (Exception e) {
                LOG.error("reconciliation attempt {} of {} failed", attempt, attempts, e);
                if (attempt < attempts) {
                    pause(retryMs);
                }
            }
        }
        LOG.error("reconciliation failed after {} attempts; staying not ready, no further retry", attempts);
    }

    private void recoverOnce(int attempt) throws Exception {
        if (attempt <= forcedFailAttempts) {
            throw new IllegalStateException("forced recovery failure (test) on attempt " + attempt);
        }
        awaitDefinitions();
        // Restored instances are registered in memory; give the engine a moment to finish restoring.
        Thread.sleep(1_000);
        List<Map<String, Object>> rows = store.instancesToRecover();
        long deadline = System.currentTimeMillis() + 20_000;
        for (Map<String, Object> row : rows) {
            String bridge = (String) row.get("bridge");
            String engineId = (String) row.get("engine_id");
            if (restoredEngineIds.contains(engineId)) {
                continue;
            }
            WorkflowDefinition d = definition("bridge-" + bridge);
            while (d.activeInstance(engineId).isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            var instance = d.activeInstance(engineId);
            if (instance.isPresent()) {
                LOG.info("starting restored instance {} of bridge {}", engineId, bridge);
                instance.get().start();
            } else {
                LOG.info("no persisted engine instance for {} (completed or never persisted)", engineId);
            }
            // Mark work complete only after start (or the confirmed absent-instance check) succeeds. If it throws,
            // the next recovery attempt retries this row instead of opening readiness with work skipped.
            restoredEngineIds.add(engineId);
        }
        // Bridge C: agent runs that were in flight in this JVM.
        Thread.sleep(1_000);
        for (Map<String, Object> run : store.runsToRecover("C-async")) {
            Ctx ctx = Ctx.fromJson((String) run.get("received_ctx"));
            String threadId = ctx.threadId().value();
            if (restartedThreads.contains(threadId)) {
                continue;
            }
            LOG.info("restarting agent run {} from status {}", ctx.threadId(), run.get("status"));
            store.recordStep(threadId, "reconciler-restart:" + run.get("status"), ctx, null);
            async.recover(ctx, (String) run.get("status"));
            // If recordStep or recover throws, leave the run eligible for the next attempt.
            restartedThreads.add(threadId);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitDefinitions() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (application.workflowDefinitions().size() >= 3) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("workflow definitions did not load");
    }

    private WorkflowDefinition definition(String name) {
        return application.workflowDefinitions().get(new WorkflowDefinitionId("spikec", name, "1.0.0"));
    }
}
