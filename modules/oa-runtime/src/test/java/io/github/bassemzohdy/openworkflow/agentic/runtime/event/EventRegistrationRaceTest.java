package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;

import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.events.AbstractTypeConsumer;
import io.serverlessworkflow.impl.events.InMemoryEvents;
import io.serverlessworkflow.impl.events.TypeEventRegistration;
import io.serverlessworkflow.impl.events.TypeEventRegistrationBuilder;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercises the actual pinned SDK mutation; reflection only inserts a scheduling barrier in its private map. */
@Timeout(15)
class EventRegistrationRaceTest {
    private static final TypeEventRegistrationBuilder FILTER =
            new TypeEventRegistrationBuilder("race.ready", (event, workflow, task) -> true);

    @Test
    void rawSdkLosesTheNewListenerWhenTheLastOldListenerIsRemovedBeforeBucketAdd() throws Exception {
        exercise(new InMemoryEvents(), false);
    }

    @Test
    void defaultBrokerKeepsTheNewListenerReachableUnderTheSameOrdering() throws Exception {
        exercise(new AtomicInMemoryEvents(), true);
    }

    @Test
    void aBlockedPublishedCallbackDoesNotHoldTheRegistrationMonitor() throws Exception {
        try (var events = new AtomicInMemoryEvents()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var registration = events.register(FILTER, event -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback barrier timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
            });
            var published = events.publish(CloudEventBuilder.v1().withId("blocked-callback")
                    .withSource(URI.create("urn:test:listener-race")).withType(FILTER.type()).build());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    var later = events.register(FILTER, event -> { });
                    events.unregister(registration);
                    events.unregister(later);
                });
                assertFalse(published.isDone(), "mutation finished while the callback remained blocked");
            } finally { release.countDown(); }
            published.get(5, TimeUnit.SECONDS);
        }
    }

    private static void exercise(InMemoryEvents events, boolean atomic) throws Exception {
        try (events) {
            var oldCalls = new AtomicInteger();
            var newCalls = new AtomicInteger();
            TypeEventRegistration old = events.register(FILTER, event -> oldCalls.incrementAndGet());
            PausedMap map = pauseMap(events);
            FutureTask<TypeEventRegistration> adding = new FutureTask<>(
                    () -> events.register(FILTER, event -> newCalls.incrementAndGet()));
            FutureTask<Void> removing = new FutureTask<>(() -> { events.unregister(old); return null; });
            Thread addThread = new Thread(adding, "listener-add");
            Thread removeThread = new Thread(removing, "listener-remove");
            try {
                addThread.start();
                assertTrue(map.bucketSelected.await(5, TimeUnit.SECONDS), "new registration selected the old bucket");
                removeThread.start();
                if (atomic) {
                    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                        var threads = ManagementFactory.getThreadMXBean();
                        while (true) {
                            var info = threads.getThreadInfo(removeThread.threadId());
                            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                                    && info.getLockOwnerId() == addThread.threadId()) break;
                            assertFalse(removing.isDone(), "removal must not enter the SDK during the paused add");
                            Thread.sleep(1);
                        }
                    });
                    assertEquals(1, map.bucketRemoved.getCount(), "no SDK removal while registration holds the monitor");
                } else {
                    removing.get(5, TimeUnit.SECONDS);
                    assertEquals(0, map.bucketRemoved.getCount(), "the last listener and topic were removed");
                }
            } finally {
                map.allowAdd.countDown();
                addThread.join(5_000);
                if (removeThread.getState() != Thread.State.NEW) removeThread.join(5_000);
            }
            TypeEventRegistration added = adding.get(5, TimeUnit.SECONDS);
            removing.get(5, TimeUnit.SECONDS);
            events.publish(CloudEventBuilder.v1().withId("after-both-mutations")
                    .withSource(URI.create("urn:test:listener-race")).withType(FILTER.type()).build())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(0, oldCalls.get());
            assertEquals(atomic ? 1 : 0, newCalls.get(),
                    "the raw SDK returns a registration in a detached bucket; the default broker preserves it");
            events.unregister(added);
            events.publish(CloudEventBuilder.v1().withId("after-unregister")
                    .withSource(URI.create("urn:test:listener-race")).withType(FILTER.type()).build())
                    .get(5, TimeUnit.SECONDS);
            assertEquals(atomic ? 1 : 0, newCalls.get(), "the surviving registration can still be removed");
        }
    }

    private static PausedMap pauseMap(InMemoryEvents events) throws Exception {
        var field = AbstractTypeConsumer.class.getDeclaredField("registrations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> existing = (Map<Object, Object>) field.get(events);
        var paused = new PausedMap(existing);
        field.set(events, paused);
        return paused;
    }

    /** Pause after the real computeIfAbsent returns its bucket, before the SDK calls bucket.add. */
    private static final class PausedMap extends ConcurrentHashMap<Object, Object> {
        final CountDownLatch bucketSelected = new CountDownLatch(1);
        final CountDownLatch allowAdd = new CountDownLatch(1);
        final CountDownLatch bucketRemoved = new CountDownLatch(1);

        PausedMap(Map<Object, Object> existing) { super(existing); }

        @Override
        public Object computeIfAbsent(Object key, Function<? super Object, ?> mapping) {
            Object bucket = super.computeIfAbsent(key, mapping);
            bucketSelected.countDown();
            try {
                if (!allowAdd.await(5, TimeUnit.SECONDS)) throw new AssertionError("registration barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return bucket;
        }

        @Override
        public Object computeIfPresent(Object key, BiFunction<? super Object, ? super Object, ?> mutation) {
            Object result = super.computeIfPresent(key, mutation);
            if (result == null) bucketRemoved.countDown();
            return result;
        }
    }
}
