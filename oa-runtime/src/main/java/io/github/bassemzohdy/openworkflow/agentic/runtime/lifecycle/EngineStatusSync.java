package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.github.bassemzohdy.openworkflow.agentic.contracts.id.Ulid;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Carries what the engine reports into the registry (WP-105c, scope item 3; ADR 0019, Cancel and Resume). The Flow
 * observer calls it when the engine emits a status change; the reconciler calls it when it polls an engine instance.
 * Both paths give the same result, so a missed event is repaired by the next poll.
 *
 * <p><b>Rules.</b> The registry moves only along {@link InstanceStateMachine}. A terminal row never changes. The
 * registry records CANCELLED only when the engine reports it (ADR 0019). A STARTING row is moved to RUNNING by an
 * engine report only when the engine has the instance, which means the start was accepted; the start path in
 * {@link InstanceService} then finds the row already moved and does not overwrite it.
 */
@Singleton
public class EngineStatusSync {

    /** The result of one report. */
    public enum Result {
        /** No registry row holds this engine instance. */
        NO_ROW,
        /** The row already reflects the report. */
        UNCHANGED,
        /** The row was moved. */
        CHANGED
    }

    private static final Logger LOG = Logger.getLogger(EngineStatusSync.class);
    private static final int MAX_ATTEMPTS = 5;

    private final InstanceStore store;
    private final RegistryScan scan;
    private final LifecycleSettings settings;
    private final Clock clock;

    @Inject
    public EngineStatusSync(InstanceStore store, RegistryScan scan, LifecycleSettings settings) {
        this(store, scan, settings, Clock.systemUTC());
    }

    public EngineStatusSync(InstanceStore store, RegistryScan scan, LifecycleSettings settings, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.scan = Objects.requireNonNull(scan, "scan");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The registry steps that an engine status requires, from the current registry status. Empty when nothing changes.
     * Pure: the table of the mapping is tested without a database.
     */
    public static List<InstanceStatus> steps(InstanceStatus current, EngineStatus engine) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(engine, "engine");
        if (current.isTerminal()) {
            return List.of();
        }
        return switch (engine) {
            case PENDING, SUSPENDED -> List.of();
            case RUNNING -> current == InstanceStatus.STARTING || current == InstanceStatus.WAITING
                    ? List.of(InstanceStatus.RUNNING) : List.of();
            case WAITING -> current == InstanceStatus.STARTING
                    ? List.of(InstanceStatus.RUNNING, InstanceStatus.WAITING)
                    : current == InstanceStatus.RUNNING ? List.of(InstanceStatus.WAITING) : List.of();
            case COMPLETED -> toTerminal(current, InstanceStatus.COMPLETED);
            case FAULTED -> toTerminal(current, InstanceStatus.FAILED);
            case CANCELLED -> current == InstanceStatus.CANCEL_REQUESTED
                    ? List.of(InstanceStatus.CANCELLED)
                    : current == InstanceStatus.STARTING
                            ? List.of(InstanceStatus.RUNNING, InstanceStatus.CANCEL_REQUESTED, InstanceStatus.CANCELLED)
                            : List.of(InstanceStatus.CANCEL_REQUESTED, InstanceStatus.CANCELLED);
        };
    }

    /**
     * Applies one engine report to the registry row that holds the engine instance.
     *
     * @param source "observed" for a Flow event, "polled" for a reconciler read, "adopted" or "started" for a repair
     */
    public Result observe(String engineInstanceId, EngineStatus status, String source) {
        Objects.requireNonNull(engineInstanceId, "engineInstanceId");
        Objects.requireNonNull(status, "status");
        Optional<RegistryScan.Ref> ref = scan.byEngineInstanceId(engineInstanceId);
        if (ref.isEmpty()) {
            return Result.NO_ROW;
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            InstanceRecord row = store.find(ref.get().tenant(), Ulid.parse(ref.get().id()))
                    .orElseThrow(() -> new IllegalStateException("registry row vanished for engine instance "
                            + engineInstanceId));
            List<InstanceStatus> steps = steps(row.status(), status);
            if (steps.isEmpty()) {
                return attempt == 0 ? Result.UNCHANGED : Result.CHANGED;
            }
            try {
                InstanceRecord current = row;
                for (InstanceStatus next : steps) {
                    current = advance(current, next, status, source);
                }
                return Result.CHANGED;
            } catch (StaleVersionException moved) {
                // Another writer changed the row between our read and our write. Read again and plan again.
                LOG.debugf("registry row %s changed while the engine status was applied; retrying", row.id());
            } catch (IllegalTransitionException impossible) {
                // The plan came from the state machine, so this is a row that changed under us into a state
                // the report does not reach. The next report or poll decides; nothing is forced.
                LOG.warnf("engine status %s not applied to %s: %s", status, row.id(), impossible.getMessage());
                return Result.UNCHANGED;
            }
        }
        throw new IllegalStateException("registry row for engine instance " + engineInstanceId
                + " kept changing while the engine status was applied");
    }

    private InstanceRecord advance(InstanceRecord current, InstanceStatus next, EngineStatus engine, String source) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Instant deadline = next == InstanceStatus.WAITING ? now.plus(settings.waitDeadline) : null;
        InstanceRecord successor = current.successor(now, next, current.engineInstanceId(),
                current.startAttempts(), current.lastError(), deadline);
        return store.replace(successor, current.version(), "engine." + source + "." + engine.name(),
                InstanceService.RUNTIME_ACTOR);
    }

    private static List<InstanceStatus> toTerminal(InstanceStatus current, InstanceStatus terminal) {
        return current == InstanceStatus.STARTING
                ? List.of(InstanceStatus.RUNNING, terminal)
                : List.of(terminal);
    }
}
