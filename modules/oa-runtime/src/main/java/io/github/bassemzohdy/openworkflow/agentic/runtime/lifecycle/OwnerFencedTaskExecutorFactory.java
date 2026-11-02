package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskBase;
import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.executors.*;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Explicit candidate around the pinned SDK's abstract executor builders. Not installed automatically. It preserves
 * guarded executors across sequential and named transitions and in the definition's resume lookup. Other builder
 * implementations fail closed and need independent compatibility proof. Refusal stops entry into the task; it is
 * not an engine cancellation report and cannot stop already-running work. Input filters need a separate guard.
 */
public final class OwnerFencedTaskExecutorFactory implements TaskExecutorFactory {
    @FunctionalInterface public interface Check { void requireCurrent(WorkflowContextData context); }
    private final TaskExecutorFactory delegate;
    private final Check check;
    public OwnerFencedTaskExecutorFactory(TaskExecutorFactory delegate, Check check) {
        this.delegate = Objects.requireNonNull(delegate); this.check = Objects.requireNonNull(check);
    }
    @Override public int priority() { return delegate.priority(); }
    @Override public TaskExecutorBuilder<? extends TaskBase> getTaskExecutor(WorkflowMutablePosition position,
                                                                             Task task, WorkflowDefinition definition) {
        return new Wrapped<>(delegate.getTaskExecutor(position, task, definition), position.copy(), definition);
    }
    private final class Wrapped<T extends TaskBase> implements TaskExecutorBuilder<T> {
        private final TaskExecutorBuilder<T> source;
        private final AbstractTaskExecutor.AbstractTaskExecutorBuilder<T, ?> abstractSource;
        private final WorkflowMutablePosition position;
        private final WorkflowDefinition definition;
        private TaskExecutor<T> instance;

        @SuppressWarnings("unchecked")
        private Wrapped(TaskExecutorBuilder<T> source, WorkflowMutablePosition position, WorkflowDefinition definition) {
            this.source = Objects.requireNonNull(source); this.position = position; this.definition = definition;
            if (!(source instanceof AbstractTaskExecutor.AbstractTaskExecutorBuilder<?, ?> builder))
                throw new IllegalArgumentException("unsupported SDK task executor builder");
            this.abstractSource = (AbstractTaskExecutor.AbstractTaskExecutorBuilder<T, ?>) builder;
        }
        @Override public void connect(Map<String, TaskExecutorBuilder<?>> connections) {
            // The pinned SDK searches values() for this *original* builder by identity, while named jumps use get().
            // Keep every target guarded, including a named jump to this task, without losing that identity search.
            var byName = Collections.unmodifiableMap(new LinkedHashMap<>(connections));
            var identityValues = new ArrayList<TaskExecutorBuilder<?>>(byName.values());
            for (int i = 0; i < identityValues.size(); i++) if (identityValues.get(i) == this) identityValues.set(i, source);
            var values = Collections.unmodifiableList(identityValues);
            source.connect(new AbstractMap<>() {
                @Override public TaskExecutorBuilder<?> get(Object key) { return byName.get(key); }
                @Override public java.util.Collection<TaskExecutorBuilder<?>> values() { return values; }
                @Override public Set<Entry<String, TaskExecutorBuilder<?>>> entrySet() { return byName.entrySet(); }
            });
        }
        @Override public synchronized TaskExecutor<T> build() {
            // SDK builders cache their original before connecting transitions, including recursive/self transitions.
            var original = source.build();
            if (instance == null) instance = new Guarded<>(abstractSource, original);
            // The SDK registers the original during build. Its restore lookup must return the guarded executor too.
            definition.addTaskExecutor(position, instance);
            return instance;
        }
    }
    private final class Guarded<T extends TaskBase> extends AbstractTaskExecutor<T> {
        private final TaskExecutor<T> original;
        private Guarded(AbstractTaskExecutorBuilder<T, ?> builder, TaskExecutor<T> original) {
            super(builder); this.original = original;
        }
        @Override public CompletableFuture<TaskContext> apply(WorkflowContext workflow, Optional<TaskContext> parent,
                                                               WorkflowModel input) {
            try { check.requireCurrent(workflow); }
            catch (RuntimeException refused) { return CompletableFuture.failedFuture(ownerLost(refused)); }
            return original.apply(workflow, parent, input);
        }
        @Override protected TransitionInfo getSkipTransition() { throw new UnsupportedOperationException("delegated executor"); }
        @Override protected CompletableFuture<TaskContext> execute(WorkflowContext workflow, TaskContext task) {
            throw new UnsupportedOperationException("delegated executor");
        }
    }
    static CancellationException ownerLost(RuntimeException cause) {
        var refused = new CancellationException("workflow owner is not current at execution boundary");
        refused.initCause(cause); return refused;
    }
}
