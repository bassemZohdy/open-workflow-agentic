package io.github.bassemzohdy.openworkflow.agentic.runtime.event;

import static org.junit.jupiter.api.Assertions.*;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.events.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Actual pinned registration/broadcast SPI, without database persistence. These observations are not T13 proof. */
class SdkEventRegistrationSemanticsTest {
    @Test void oneEventCanSatisfyBothIdenticalAllRegistrationsInThePinnedSdk() throws Exception {
        try(var events=new InMemoryEvents()) {
            var filter=new TypeEventRegistrationBuilder("order.ready",(ce,w,t)->true);
            var builders=new EventRegistrationBuilderCollection(List.of(filter,filter),true);
            var received=new ArrayList<String>();
            var info=EventRegistrationInfo.build(builders,(ce,future)-> {
                received.add(ce.getId());future.complete(ce.getId());
            },events,null,null); // Literal predicates need no workflow/task or correlation context.
            info.completableFuture().whenComplete((__,error)->info.registrations().forEach(r->events.unregister((TypeEventRegistration)r)));
            assertFalse(info.completableFuture().isDone());
            events.publish(CloudEventBuilder.v1().withId("one").withSource(URI.create("urn:test:orders"))
                    .withType("order.ready").build()).get(5,TimeUnit.SECONDS);
            info.completableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(List.of("one","one"),received,"filter ordinal and receipt identity are different concepts");
        }
    }

    @Test void defaultAnyFanoutCanInvokeAnotherCallbackEvenAfterTheWinnerUnregisters() throws Exception {
        try(var events=new InMemoryEvents()) {
            var filter=new TypeEventRegistrationBuilder("order.ready",(ce,w,t)->true);
            var builders=new EventRegistrationBuilderCollection(List.of(filter,filter),false);
            var received=new ArrayList<String>();
            var info=EventRegistrationInfo.build(builders,(ce,future)-> {
                received.add(ce.getId());future.complete(ce.getId());
            },events,null,null);
            info.completableFuture().whenComplete((__,error)->info.registrations().forEach(r->events.unregister((TypeEventRegistration)r)));
            events.publish(CloudEventBuilder.v1().withId("one").withSource(URI.create("urn:test:orders"))
                    .withType("order.ready").build()).get(5,TimeUnit.SECONDS);
            info.completableFuture().get(5,TimeUnit.SECONDS);
            assertEquals(2,received.size(),"unregistration alone cannot fence a callback in a captured broadcast snapshot");
            events.publish(CloudEventBuilder.v1().withId("later").withSource(URI.create("urn:test:orders"))
                    .withType("order.ready").build()).get(5,TimeUnit.SECONDS);
            assertEquals(2,received.size(),"the earlier second callback came from the captured snapshot, not a retained subscription");
        }
    }

    @Test void failingEarlierSubscriberPreventsLaterDeliveryUntilItIsRemoved() throws Exception {
        try(var events=new InMemoryEvents()) {
            var first=events.register(new TypeEventRegistrationBuilder("order.ready",(ce,w,t)->{
                throw new IllegalStateException("fixture predicate failure");
            }),ce->fail("failing predicate must not deliver"));
            var received=new java.util.concurrent.atomic.AtomicInteger();
            events.register(new TypeEventRegistrationBuilder("order.ready",(ce,w,t)->true),ce->received.incrementAndGet());
            var event=CloudEventBuilder.v1().withId("one").withSource(URI.create("urn:test:orders")).withType("order.ready").build();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->events.publish(event).get(5,TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,failure.getCause());
            assertEquals(0,received.get(),"the later registration is not offered the event after the earlier failure");
            events.unregister(first);
            events.publish(event).get(5,TimeUnit.SECONDS);
            assertEquals(1,received.get(),"the same later registration receives the event once the failing one is removed");
        }
    }

    @Test void failingEarlierCallbackAlsoPreventsLaterDelivery() throws Exception {
        try(var events=new InMemoryEvents()) {
            var first=events.register(new TypeEventRegistrationBuilder("order.ready",(ce,w,t)->true),ce->{
                throw new IllegalStateException("fixture callback failure");
            });
            var received=new java.util.concurrent.atomic.AtomicInteger();
            events.register(new TypeEventRegistrationBuilder("order.ready",(ce,w,t)->true),ce->received.incrementAndGet());
            var event=CloudEventBuilder.v1().withId("one").withSource(URI.create("urn:test:orders")).withType("order.ready").build();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->events.publish(event).get(5,TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,failure.getCause());
            assertEquals(0,received.get());
            events.unregister(first);
            events.publish(event).get(5,TimeUnit.SECONDS);
            assertEquals(1,received.get());
        }
    }
}

