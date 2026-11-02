package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FlowWorkflowEngineStartRaceTest {
    @Test void concurrentStartsDoNotEnterTheSamePendingInstanceTwice() throws Exception {
        var status=new AtomicReference<>(WorkflowStatus.PENDING);var starts=new AtomicInteger();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        WorkflowInstance instance=instance(status,()->{int attempt=starts.incrementAndGet();entered.countDown();
            try { if(attempt==1 && !release.await(5,TimeUnit.SECONDS))throw new AssertionError("start was not released"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            status.compareAndSet(WorkflowStatus.PENDING,WorkflowStatus.RUNNING);return CompletableFuture.completedFuture(null);});
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=workers.submit(()->FlowWorkflowEngine.startPending(instance));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertFalse(workers.submit(()->FlowWorkflowEngine.startPending(instance)).get(5,TimeUnit.SECONDS));
                assertEquals(1,starts.get());
            } finally {release.countDown();}
            assertTrue(first.get(5,TimeUnit.SECONDS));
            assertFalse(FlowWorkflowEngine.startPending(instance));assertEquals(1,starts.get());
        }
    }

    @Test void uncertainStartIsNotRepeatedWhileInstanceStillLooksPending() {
        var status=new AtomicReference<>(WorkflowStatus.PENDING);var starts=new AtomicInteger();
        WorkflowInstance instance=instance(status,()->{starts.incrementAndGet();throw new IllegalStateException("uncertain start");});
        assertThrows(IllegalStateException.class,()->FlowWorkflowEngine.startPending(instance));
        assertFalse(FlowWorkflowEngine.startPending(instance));assertEquals(1,starts.get());
    }

    @Test void pendingClaimDoesNotBlockAnotherInstance() throws Exception {
        var firstStatus=new AtomicReference<>(WorkflowStatus.PENDING);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        WorkflowInstance first=instance(firstStatus,()->{entered.countDown();
            try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("start was not released");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            firstStatus.set(WorkflowStatus.RUNNING);return CompletableFuture.completedFuture(null);});
        var otherStatus=new AtomicReference<>(WorkflowStatus.PENDING);
        WorkflowInstance other=instance(otherStatus,()->{otherStatus.set(WorkflowStatus.RUNNING);return CompletableFuture.completedFuture(null);});
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=workers.submit(()->FlowWorkflowEngine.startPending(first));
            try {assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(workers.submit(()->FlowWorkflowEngine.startPending(other)).get(5,TimeUnit.SECONDS));}
            finally {release.countDown();}
            assertTrue(pending.get(5,TimeUnit.SECONDS));
        }
    }

    @Test void blockedStatusCheckDoesNotBlockAnotherInstance() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var firstStatus=new AtomicReference<>(WorkflowStatus.PENDING);
        WorkflowInstance first=(WorkflowInstance)Proxy.newProxyInstance(WorkflowInstance.class.getClassLoader(),
                new Class<?>[]{WorkflowInstance.class},(proxy,method,args)->switch(method.getName()) {
                    case "status" -> {entered.countDown();
                        if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("status was not released");
                        yield firstStatus.get();}
                    case "start" -> {firstStatus.set(WorkflowStatus.RUNNING);yield CompletableFuture.completedFuture(null);}
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var otherStatus=new AtomicReference<>(WorkflowStatus.PENDING);
        WorkflowInstance other=instance(otherStatus,()->{otherStatus.set(WorkflowStatus.RUNNING);return CompletableFuture.completedFuture(null);});
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=workers.submit(()->FlowWorkflowEngine.startPending(first));
            try {assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(workers.submit(()->FlowWorkflowEngine.startPending(other)).get(5,TimeUnit.SECONDS));}
            finally {release.countDown();}
            assertTrue(pending.get(5,TimeUnit.SECONDS));
        }
    }

    @Test void cancellationIsNotBlockedByAnInProgressStart() throws Exception {
        var status=new AtomicReference<>(WorkflowStatus.PENDING);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        WorkflowInstance instance=instance(status,()->{entered.countDown();
            try { if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("start was not released"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            status.compareAndSet(WorkflowStatus.PENDING,WorkflowStatus.RUNNING);return CompletableFuture.completedFuture(null);});
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=workers.submit(()->FlowWorkflowEngine.startPending(instance));
            try {assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(workers.submit(instance::cancel).get(5,TimeUnit.SECONDS));}
            finally {release.countDown();}
            assertTrue(first.get(5,TimeUnit.SECONDS));assertEquals(WorkflowStatus.CANCELLED,instance.status());
            assertFalse(FlowWorkflowEngine.startPending(instance));
        }
    }

    private static WorkflowInstance instance(AtomicReference<WorkflowStatus> status,
                                              java.util.function.Supplier<CompletableFuture<?>> start) {
        return (WorkflowInstance) Proxy.newProxyInstance(WorkflowInstance.class.getClassLoader(),new Class<?>[]{WorkflowInstance.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "status" -> status.get();
                    case "start" -> start.get();
                    case "cancel" -> {status.set(WorkflowStatus.CANCELLED);yield true;}
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy==args[0];
                    case "toString" -> "test-workflow-instance";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
