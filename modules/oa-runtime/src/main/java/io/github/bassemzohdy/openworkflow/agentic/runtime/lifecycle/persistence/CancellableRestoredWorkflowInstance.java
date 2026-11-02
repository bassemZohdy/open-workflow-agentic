/*
 * Copyright 2020-Present The Serverless Workflow Specification Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.persistence;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.WorkflowOwnerCoordinator;
import io.serverlessworkflow.impl.persistence.PersistenceWorkflowInfo;
import io.serverlessworkflow.impl.persistence.PersistenceTaskInfo;
import io.serverlessworkflow.impl.persistence.CompletedTaskInfo;
import io.serverlessworkflow.impl.persistence.RetriedTaskInfo;
import io.serverlessworkflow.impl.persistence.PersistenceInstanceWriter;
import io.serverlessworkflow.impl.persistence.WorkflowPersistenceListener;
import io.serverlessworkflow.impl.lifecycle.WorkflowCancelledEvent;
import io.serverlessworkflow.impl.LifecycleEventsUtils;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import io.serverlessworkflow.api.types.TryTask;
import io.serverlessworkflow.impl.TaskContext;
import io.serverlessworkflow.impl.WorkflowContext;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import io.serverlessworkflow.impl.WorkflowMutableInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.executors.TransitionInfo;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Opt-in restore implementation adapted from the pinned SDK's WorkflowPersistenceInstance (Apache-2.0).
 * Pending cancellation prevents stale starts immediately, but only publishes CANCELLED after the checkpoint writer
 * confirms removal. No registry identity or cross-replica lease is established here. This is not production wiring.
 */
public final class CancellableRestoredWorkflowInstance extends WorkflowMutableInstance {
  private final Object lifecycle = new Object();
  private final PersistenceInstanceWriter cancellationWriter;
  private final CancellablePersistenceReader reader;
  private final CancellablePersistenceReader.OwnerStarter ownerStarter;
  private CompletableFuture<WorkflowModel> guardedStart;
  private boolean started;
  private volatile boolean pendingCancellationRequested;
  private boolean removalAcknowledged;
  private CompletableFuture<Boolean> pendingCancellation;

  private final PersistenceWorkflowInfo info;
  private Set<String> userMetaKeys = new HashSet<>();

  static WorkflowInstance of(WorkflowDefinition definition, PersistenceWorkflowInfo info,
                             PersistenceInstanceWriter writer, CancellablePersistenceReader reader,
                             CancellablePersistenceReader.OwnerStarter ownerStarter) {
    synchronized (definition) {
      var existing = definition.activeInstance(info.id());
      if (existing.isPresent()) {
        if (!(existing.get() instanceof CancellableRestoredWorkflowInstance guarded) || guarded.reader != reader)
          throw new IllegalStateException("existing restored instance belongs to another reader");
        return guarded;
      }
      return new CancellableRestoredWorkflowInstance(definition, info, writer, reader, ownerStarter);
    }
  }

  private CancellableRestoredWorkflowInstance(WorkflowDefinition definition, PersistenceWorkflowInfo info,
                                               PersistenceInstanceWriter writer, CancellablePersistenceReader reader,
                                               CancellablePersistenceReader.OwnerStarter ownerStarter) {
    super(definition, info.id(), info.input());
    this.cancellationWriter = Objects.requireNonNull(writer, "writer");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.ownerStarter = ownerStarter;
    this.info = info;
    info.tasks()
        .forEach(
            (k, v) -> {
              if (v instanceof CompletedTaskInfo task) {
                iterationsMap.put(k, task.iteration());
              }
            });
    this.startedAt = info.startedAt();
    additionalObjects.putAll(info.metadata());
  }

  @Override
  public CompletableFuture<WorkflowModel> start() {
    if (ownerStarter == null) {
      synchronized (lifecycle) {
        if (pendingCancellationRequested)
          return CompletableFuture.failedFuture(new CancellationException("restored instance was cancelled"));
        started = true;
        return startRestoredExecution();
      }
    }
    CompletableFuture<WorkflowModel> attempt;
    synchronized (lifecycle) {
      if (pendingCancellationRequested)
        return CompletableFuture.failedFuture(new CancellationException("restored instance was cancelled"));
      if (guardedStart != null) return guardedStart;
      guardedStart = new CompletableFuture<>();
      attempt = guardedStart;
    }
    WorkflowOwnerCoordinator.Session session = null;
    try {
      // Acquisition can block: never hold the lifecycle monitor while cancellation is trying to prevent a start.
      var acquired = Objects.requireNonNull(ownerStarter.acquire(workflowContext), "owner acquisition");
      if (acquired.isEmpty()) {
        synchronized (lifecycle) { if (guardedStart == attempt) guardedStart = null; }
        attempt.completeExceptionally(new IllegalStateException("restored workflow has no current owner"));
        return attempt;
      }
      session = acquired.get();
      CompletableFuture<WorkflowModel> execution;
      synchronized (lifecycle) {
        if (pendingCancellationRequested) {
          session.close();
          attempt.completeExceptionally(new CancellationException("restored instance was cancelled during acquisition"));
          return attempt;
        }
        started = true;
        execution = startRestoredExecution();
      }
      var active = session;
      execution.whenComplete((value, failure) -> {
        active.close();
        if (failure == null) attempt.complete(value); else attempt.completeExceptionally(failure);
      });
      return attempt;
    } catch (RuntimeException | Error failure) {
      if (session != null) session.close();
      // Once a context has been bound, it must never be rebound to another owner generation.
      synchronized (lifecycle) {
        if (session == null && !(failure instanceof CancellablePersistenceReader.RetiredOwnerContextException)
            && guardedStart == attempt) guardedStart = null;
      }
      attempt.completeExceptionally(failure);
      if (failure instanceof Error fatal) throw fatal;
      return attempt;
    }
  }

  private CompletableFuture<WorkflowModel> startRestoredExecution() {
    return startExecution(
        () -> {
          if (info.status() == WorkflowStatus.SUSPENDED) {
            setSuspended();
            setStatus(WorkflowStatus.SUSPENDED);
          } else {
            setStatus(WorkflowStatus.RUNNING);
          }
          return CompletableFuture.completedFuture(null);
        });
  }

  @Override
  public CompletableFuture<Boolean> status(WorkflowStatus state) {
    synchronized (lifecycle) {
      if ((!started && !pendingCancellationRequested && state != WorkflowStatus.PENDING)
          || (pendingCancellationRequested && (!removalAcknowledged || state != WorkflowStatus.CANCELLED)))
        return CompletableFuture.failedFuture(new IllegalStateException("restored instance cannot change status"));
      return super.status(state);
    }
  }

  @Override
  public boolean cancel() {
    synchronized (lifecycle) {
      if (pendingCancellationRequested) {
        if (pendingCancellation.isCompletedExceptionally() && !removalAcknowledged) beginPendingCancellation();
        return !pendingCancellation.isCompletedExceptionally();
      }
      if (started || status() != WorkflowStatus.PENDING) return super.cancel();
      beginPendingCancellation();
      return !pendingCancellation.isCompletedExceptionally();
    }
  }

  @Override
  public CompletableFuture<Boolean> cancelFuture() {
    synchronized (lifecycle) {
      if (pendingCancellationRequested) {
        if (pendingCancellation.isCompletedExceptionally() && !removalAcknowledged) beginPendingCancellation();
        return pendingCancellation.copy();
      }
      if (started || status() != WorkflowStatus.PENDING) return super.cancelFuture();
      beginPendingCancellation();
      return pendingCancellation.copy();
    }
  }

  /** Data for the explicitly composed JPA receipt writer; not an execution capability. */
  boolean pendingCancellationRequested() { return pendingCancellationRequested; }

  /** Called under lifecycle; the pending flag is set before any checkpoint I/O can block. */
  private void beginPendingCancellation() {
    pendingCancellationRequested = true;
    pendingCancellation = new CompletableFuture<>();
    try {
      reader.prohibitRestore(workflowContext.definition(), id);
      var removal = Objects.requireNonNull(cancellationWriter.aborted(workflowContext), "removal future");
      removal.whenComplete((ignored, error) -> {
        synchronized (lifecycle) {
          if (error != null) { pendingCancellation.completeExceptionally(error); return; }
          try {
            // Only acknowledged durable removal may make the SDK status visible as CANCELLED.
            removalAcknowledged = true;
            status(WorkflowStatus.CANCELLED).thenCompose(ignoredStatus ->
                LifecycleEventsUtils.publishEvent(workflowContext, listener ->
                    listener instanceof WorkflowPersistenceListener ? CompletableFuture.completedFuture(null)
                        : listener.onWorkflowCancelled(new WorkflowCancelledEvent(workflowContext))))
                .whenComplete((ignoredEvent, eventError) -> {
                  synchronized (lifecycle) {
                    // A failed pre-execution future triggers SDK cleanup without executing any task.
                    startExecution(() -> CompletableFuture.failedFuture(new CancellationException("cancelled before restore")));
                    if (eventError == null) pendingCancellation.complete(true);
                    else pendingCancellation.completeExceptionally(eventError);
                  }
                });
          } catch (RuntimeException failure) {
            pendingCancellation.completeExceptionally(failure);
          }
        }
      });
    } catch (RuntimeException failure) {
      pendingCancellation.completeExceptionally(failure);
    }
  }

  @Override
  public void restoreContext(WorkflowContext workflow, TaskContext context) {
    if (info.tasks().isEmpty()) {
      return;
    }
    PersistenceTaskInfo taskInfo = info.tasks().remove(context.position().jsonPointer());
    if (taskInfo == null) {
      return;
    }
    if (taskInfo instanceof CompletedTaskInfo completedTaskInfo) {
      context.output(completedTaskInfo.model());
      context.completedAt(completedTaskInfo.instant());
      context.transition(
          new TransitionInfo(
              completedTaskInfo.nextPosition() == null
                  ? null
                  : workflow.definition().taskExecutor(completedTaskInfo.nextPosition()),
              completedTaskInfo.isEndNode()));
      workflow.context(completedTaskInfo.context());
    } else if (taskInfo instanceof RetriedTaskInfo retriedTaskInfo) {
      if (context.retryAttempt() == 0) {
        context.retryAttempt(retriedTaskInfo.retryAttempt());
      }
      Optional<TaskContext> searchContext = context.parent();
      while (searchContext.isPresent()) {
        TaskContext tryContext = searchContext.orElseThrow();
        if (tryContext.task() instanceof TryTask) {
          if (tryContext.tryRetryCount().isEmpty()) {
            tryContext.tryRetryCount(retriedTaskInfo.retryAttempt());
          }
          break;
        }
        searchContext = tryContext.parent();
      }
    }
    Set<String> retainKeys;
    synchronized (userMetaKeys) {
      retainKeys = new HashSet<>(userMetaKeys);
      taskInfo
          .additionalObjects()
          .forEach(
              (k, v) -> {
                if (!retainKeys.contains(k)) {
                  additionalObjects.put(k, v);
                  retainKeys.add(k);
                }
              });
      additionalObjects.keySet().retainAll(retainKeys);
    }
  }

  @Override
  public <T> T addMetadataIfAbsent(String key, Supplier<T> supplier) {
    synchronized (userMetaKeys) {
      userMetaKeys.add(key);
      return super.addMetadataIfAbsent(key, supplier);
    }
  }

  @Override
  public void removeMetadata(String key) {
    synchronized (userMetaKeys) {
      userMetaKeys.add(key);
      super.removeMetadata(key);
    }
  }

  @Override
  public <T> Optional<T> removeMetadata(String key, Class<T> clazz) {
    synchronized (userMetaKeys) {
      userMetaKeys.add(key);
      return super.removeMetadata(key, clazz);
    }
  }
}
