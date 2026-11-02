package io.github.bassemzohdy.openworkflow.agentic.runtime.agent;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;

/** Per-run admission and physical-termination barrier for synchronous graph node actions. */
final class NodeActivityTracker {
    private boolean closing;
    private boolean emitterScheduled;
    private int active;

    /** Owns the graph emitter as well as node actions, including time spent writing checkpoints. */
    Executor emitterExecutor() {
        return work -> {
            Objects.requireNonNull(work);
            synchronized (this) {
                if (closing || emitterScheduled) throw new RejectedExecutionException("graph emitter unavailable");
                emitterScheduled = true;
                active++;
            }
            try {
                Thread.ofVirtual().name("governed-research-emitter").inheritInheritableThreadLocals(false)
                        .start(() -> { try { work.run(); } finally { finished(); } });
            } catch (RuntimeException | Error failure) {
                finished();
                throw failure;
            }
        };
    }

    <S extends AgentState> NodeAction<S> track(NodeAction<S> action) {
        Objects.requireNonNull(action);
        return state -> {
            synchronized (this) {
                if (closing) throw new ModelCallRefused("graph execution is closing");
                active++;
            }
            try { return action.apply(state); }
            finally { finished(); }
        };
    }

    private synchronized void finished() {
        active--;
        if (active == 0) notifyAll();
    }

    /** Refuse later starts and retain the caller until all admitted node actions physically return. */
    void closeAndAwait() {
        boolean interrupted = false;
        synchronized (this) {
            closing = true;
            while (active != 0) {
                try { wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
