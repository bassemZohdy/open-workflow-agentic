package io.github.bassemzohdy.openworkflow.agentic.outbox;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bassemzohdy.openworkflow.agentic.lifecycle.PendingCancellationDatabase;
import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.JdbcInstanceStore;
import io.github.bassemzohdy.openworkflow.agentic.runtime.outbox.*;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(OutboxProfile.class)
@QuarkusTestResource(value = PendingCancellationDatabase.class, restrictToAnnotatedClass = true)
class OutboxRelayTimeoutTest {
    @Inject DataSource data;
    @Inject JdbcInstanceStore instances;

    @Test void idleConsumerLosesLocksAndAnotherRelayCommitsWithoutPartialWrites() throws Exception {
        var id = OutboxFixture.newInstance(instances, OutboxFixture.newTenant());
        try (var c = data.getConnection(); var s = c.createStatement()) {
            s.execute("create table task.test_relay_effect (event_id bigint primary key)");
        }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        OutboxConsumer blocked = new OutboxConsumer() {
            public String name() { return "blocked"; }
            public void apply(Connection c, OutboxEvent event) throws SQLException {
                try (var q = c.prepareStatement("insert into task.test_relay_effect values (?)")) {
                    q.setLong(1, event.id()); q.executeUpdate();
                }
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
        };
        OutboxConsumer recovery = new OutboxConsumer() {
            public String name() { return "recovery"; }
            public void apply(Connection c, OutboxEvent event) throws SQLException {
                try (var q = c.prepareStatement("insert into task.test_relay_effect values (?)")) {
                    q.setLong(1, event.id()); q.executeUpdate();
                }
            }
        };
        var settings = new RelaySettings(1, 3, Duration.ZERO, Duration.ZERO, Duration.ofMillis(50), 500, 3000, 1000);
        var first = new OutboxRelay(data, List.of(blocked), settings);
        var second = new OutboxRelay(data, List.of(recovery), settings);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var failed = executor.submit(first::drainOnce);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(0, second.drainOnce());
                assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                    while (second.drainOnce() == 0) Thread.sleep(100);
                });
                release.countDown();
                assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
                try (var c = data.getConnection(); var q = c.prepareStatement("""
                        select (select count(*) from task.test_relay_effect) as effects,
                               count(*) filter (where published_at is not null) as published
                        from task.outbox where aggregate_id=?
                        """)) {
                    q.setString(1, id.value());
                    try (var rows = q.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(1, rows.getInt(2)); }
                }
            } finally { release.countDown(); }
        }
    }
}
