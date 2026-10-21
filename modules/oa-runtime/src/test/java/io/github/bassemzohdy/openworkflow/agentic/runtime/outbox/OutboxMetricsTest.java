package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import static org.junit.jupiter.api.Assertions.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class OutboxMetricsTest {
    private static double value(MeterRegistry registry,String name) {return registry.get(name).gauge().value();}
    private static OutboxMetrics start(Supplier<OutboxAdmin.Lag> read,MeterRegistry registry,AtomicLong nanos) {
        var metrics=new OutboxMetrics(read,registry,nanos::get);metrics.onStart(null);return metrics;
    }

    @Test void failedFirstObservationIsUnknownAndFailureAttemptsAreCachedAcrossAllGauges() {
        var calls=new AtomicInteger();var now=new AtomicLong();
        var registry=new SimpleMeterRegistry();
        try {
            var metrics=start(()->{calls.incrementAndGet();throw new IllegalStateException("private backend detail");},registry,now);
            assertTrue(Double.isNaN(value(registry,OutboxMetrics.UNPUBLISHED)));
            assertTrue(Double.isNaN(value(registry,OutboxMetrics.FAILED)));
            assertTrue(Double.isNaN(value(registry,OutboxMetrics.OLDEST_AGE)));
            assertTrue(Double.isNaN(value(registry,OutboxMetrics.AGE)));
            assertEquals(0,value(registry,OutboxMetrics.HEALTHY));assertEquals(1,calls.get());
            now.set(999_999_999);assertEquals(0,value(registry,OutboxMetrics.HEALTHY));assertEquals(1,calls.get());
            now.incrementAndGet();assertEquals(0,value(registry,OutboxMetrics.HEALTHY));assertEquals(2,calls.get());
            assertNotNull(metrics); // Registry callbacks must retain the observed owner for the test lifetime.
        } finally {registry.close();}
    }

    @Test void successfulSampleIsRetainedButMarkedUnhealthyAndAgesUntilRecovery() {
        var calls=new AtomicInteger();var now=new AtomicLong();
        var registry=new SimpleMeterRegistry();
        try {
            var metrics=start(()->switch(calls.incrementAndGet()) {
                case 1 -> new OutboxAdmin.Lag(7,2,30);
                case 2 -> throw new IllegalStateException("private detail");
                default -> new OutboxAdmin.Lag(3,0,5);
            },registry,now);
            assertEquals(7,value(registry,OutboxMetrics.UNPUBLISHED));
            assertEquals(1,value(registry,OutboxMetrics.HEALTHY));assertEquals(0,value(registry,OutboxMetrics.AGE));
            assertEquals(1,calls.get()); // nanoTime may start at zero: that is still a real cached observation.
            now.set(1_000_000_000L);
            assertEquals(0,value(registry,OutboxMetrics.HEALTHY));assertEquals(7,value(registry,OutboxMetrics.UNPUBLISHED));
            assertEquals(2,value(registry,OutboxMetrics.FAILED));assertEquals(30,value(registry,OutboxMetrics.OLDEST_AGE));
            assertEquals(1,value(registry,OutboxMetrics.AGE));assertEquals(2,calls.get());
            now.set(1_500_000_000L);assertEquals(1.5,value(registry,OutboxMetrics.AGE));assertEquals(2,calls.get());
            now.set(5_000_000_000L);assertEquals(3,value(registry,OutboxMetrics.UNPUBLISHED));
            assertEquals(1,value(registry,OutboxMetrics.HEALTHY));assertEquals(0,value(registry,OutboxMetrics.FAILED));
            assertEquals(0,value(registry,OutboxMetrics.AGE));assertEquals(3,calls.get());assertNotNull(metrics);
        } finally {registry.close();}
    }

    @Test void aBlockedObservationDoesNotStartASecondDatabaseReadOnConcurrentScrapes() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var registry=new SimpleMeterRegistry();
        try(var threads=Executors.newSingleThreadExecutor()) {
            var metrics=start(()->{calls.incrementAndGet();entered.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test barrier expired");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return new OutboxAdmin.Lag(4,0,2);
            },registry,new AtomicLong());
            var pending=threads.submit(()->value(registry,OutboxMetrics.UNPUBLISHED));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                assertEquals(0,value(registry,OutboxMetrics.HEALTHY));
                assertTrue(Double.isNaN(value(registry,OutboxMetrics.UNPUBLISHED)));assertEquals(1,calls.get());
            } finally {release.countDown();}
            assertEquals(4,pending.get(5,TimeUnit.SECONDS));assertEquals(1,value(registry,OutboxMetrics.HEALTHY));
            assertEquals(1,calls.get());assertNotNull(metrics);
        } finally {registry.close();}
    }

    @Test void realPrometheusRegistryExportsUnknownFailureAndRecoveryWithExactNamesAndNoPrivateLabels() throws Exception {
        var calls=new AtomicInteger();var now=new AtomicLong();
        var registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics=start(()->{if(calls.incrementAndGet()==1)throw new IllegalStateException("secret-token-human-task-tenant");
                return new OutboxAdmin.Lag(2,1,6);},registry,now);
            String failed=registry.scrape();
            for(String name:new String[]{"agentic_outbox_unpublished","agentic_outbox_failed","agentic_outbox_unpublished_oldest_age_seconds","agentic_outbox_observation_age_seconds"})
                assertTrue(failed.contains(name+" NaN"),failed);
            assertTrue(failed.contains("agentic_outbox_observation_healthy 0.0"),failed);
            assertFalse(failed.contains("secret-token-human-task-tenant"));assertEquals(1,calls.get());
            now.set(1_000_000_000L);String recovered=registry.scrape();
            assertTrue(recovered.contains("agentic_outbox_unpublished 2.0"),recovered);
            assertTrue(recovered.contains("agentic_outbox_failed 1.0"),recovered);
            assertTrue(recovered.contains("agentic_outbox_observation_healthy 1.0"),recovered);
            assertTrue(recovered.contains("agentic_outbox_observation_age_seconds 0.0"),recovered);
            assertEquals(5,registry.getMeters().size());
            assertTrue(registry.getMeters().stream().allMatch(m->m.getId().getTags().isEmpty()));
            assertEquals(2,calls.get());assertNotNull(metrics);
            var directory=java.nio.file.Path.of("target","observability");java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.writeString(directory.resolve("metrics-initial.prom"),failed);
            java.nio.file.Files.writeString(directory.resolve("metrics-recovered.prom"),recovered);
        } finally {registry.close();}
    }
}
