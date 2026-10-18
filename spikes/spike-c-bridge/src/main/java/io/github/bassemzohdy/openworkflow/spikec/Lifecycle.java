package io.github.bassemzohdy.openworkflow.spikec;

import io.quarkus.runtime.StartupEvent;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowDefinitionId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup and recovery (ADR 0019, "resume after restart"). Quarkus Flow restores waiting instances but does not start
 * them (WP-003 fact 1), so this reconciler calls start() on every restored instance that the registry says was running,
 * and then restarts the bridge C agent runs that a kill -9 interrupted. It runs once, before the API reports ready.
 *
 * <p>Set {@code spikec.reconcile.enabled=false} to see the engine's behaviour without it (control test).
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
        try {
            awaitDefinitions();
            // Restored instances are registered in memory; give the engine a moment to finish restoring.
            Thread.sleep(1_000);
            List<Map<String, Object>> rows = store.instancesToRecover();
            long deadline = System.currentTimeMillis() + 20_000;
            for (Map<String, Object> row : rows) {
                String bridge = (String) row.get("bridge");
                String engineId = (String) row.get("engine_id");
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
            }
            // Bridge C: agent runs that were in flight in this JVM.
            Thread.sleep(1_000);
            for (Map<String, Object> run : store.runsToRecover("C-async")) {
                Ctx ctx = Ctx.fromJson((String) run.get("received_ctx"));
                LOG.info("restarting agent run {} from status {}", ctx.threadId(), run.get("status"));
                store.recordStep(ctx.threadId().value(), "reconciler-restart:" + run.get("status"), ctx, null);
                async.recover(ctx, (String) run.get("status"));
            }
        } catch (Exception e) {
            LOG.error("reconciliation failed", e);
        } finally {
            ready = true;
            LOG.info("reconciliation complete; ready");
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
