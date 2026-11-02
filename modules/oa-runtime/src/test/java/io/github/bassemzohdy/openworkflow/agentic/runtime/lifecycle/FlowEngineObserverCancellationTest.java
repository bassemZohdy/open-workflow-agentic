package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.serverlessworkflow.impl.*;
import io.serverlessworkflow.impl.lifecycle.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class FlowEngineObserverCancellationTest {
    private static final class RecordingSync extends EngineStatusSync {
        final List<EngineStatus> observations = new ArrayList<>();
        boolean failCancellationReport;
        RecordingSync() {
            super(proxy(InstanceStore.class), new RegistryScan(proxy(DataSource.class)),
                    new LifecycleSettings(false, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                            1, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 10, Optional.empty()));
        }
        @Override public Result observe(String id, EngineStatus status, String source) {
            if (status == EngineStatus.CANCELLED && failCancellationReport) throw new IllegalStateException("store unavailable");
            observations.add(status); return Result.CHANGED;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("id")) return "engine-1";
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("instanceData")) return proxy(WorkflowInstanceData.class);
            return null;
        });
    }

    @Test void cancelledTaskWrappedAsTimeoutWaitsForTheCancellationEvent() {
        var sync = new RecordingSync(); var observer = new FlowEngineObserver(sync);
        var context = proxy(WorkflowContextData.class);
        observer.onWorkflowStatusChanged(new WorkflowStatusEvent(context, WorkflowStatus.CANCELLED, WorkflowStatus.FAULTED));
        observer.onWorkflowFailed(new WorkflowFailedEvent(context, new WorkflowException(WorkflowError.timeout().build(),
                new CompletionException(new CancellationException()))));
        assertTrue(sync.observations.isEmpty());
        observer.onWorkflowCancelled(new WorkflowCancelledEvent(context));
        assertEquals(List.of(EngineStatus.CANCELLED), sync.observations);
    }

    @Test void realTimeoutAndUnrelatedCancellationStillReportFailure() {
        var sync = new RecordingSync(); var observer = new FlowEngineObserver(sync);
        var genuine = proxy(WorkflowContextData.class);
        observer.onWorkflowStatusChanged(new WorkflowStatusEvent(genuine, WorkflowStatus.CANCELLED, WorkflowStatus.FAULTED));
        observer.onWorkflowFailed(new WorkflowFailedEvent(genuine, new WorkflowException(WorkflowError.timeout().build(),
                new TimeoutException())));
        var unrelated = proxy(WorkflowContextData.class);
        observer.onWorkflowFailed(new WorkflowFailedEvent(unrelated, new WorkflowException(WorkflowError.timeout().build(),
                new CancellationException())));
        assertEquals(List.of(EngineStatus.FAULTED, EngineStatus.FAULTED), sync.observations);
    }

    @Test void failedCancellationReportCannotTurnALaterCancellationWrapperIntoFailure() {
        var sync = new RecordingSync(); sync.failCancellationReport = true;
        var observer = new FlowEngineObserver(sync); var context = proxy(WorkflowContextData.class);
        observer.onWorkflowStatusChanged(new WorkflowStatusEvent(context, WorkflowStatus.CANCELLED, WorkflowStatus.FAULTED));
        observer.onWorkflowCancelled(new WorkflowCancelledEvent(context));
        observer.onWorkflowFailed(new WorkflowFailedEvent(context, new WorkflowException(WorkflowError.timeout().build(),
                new CancellationException())));
        assertTrue(sync.observations.isEmpty());
    }
}
