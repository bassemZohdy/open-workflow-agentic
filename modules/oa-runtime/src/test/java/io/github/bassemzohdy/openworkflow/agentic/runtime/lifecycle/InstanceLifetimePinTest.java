package io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bassemzohdy.openworkflow.agentic.contracts.id.UlidGenerator;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.Subject;
import io.github.bassemzohdy.openworkflow.agentic.contracts.identity.SubjectKind;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

/** Original lifetime is durable with instance creation; current settings can tighten, not extend it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstanceLifetimePinTest {
    private PGSimpleDataSource db, admin;
    private String name;
    private boolean created;
    private final Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private final Subject owner = new Subject(SubjectKind.USER, "alice");
    private final DefinitionRef definition = new DefinitionRef("agentic/lifetime-test", "1");
    private JdbcInstanceStore store;
    private RegistryScan scan;

    private static PGSimpleDataSource source(String database) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/agentic"));
        if (database != null) ds.setDatabaseName(database);
        ds.setUser(System.getenv().getOrDefault("DB_USER", "postgres"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "postgres")); return ds;
    }
    @BeforeAll void setup() throws Exception {
        admin = source("postgres"); name = "owa_lifetime_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(name.matches("owa_lifetime_[0-9a-f]{32}"));
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("create database " + name); created = true; }
        db = source(name); Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();
        store = new JdbcInstanceStore(db); scan = new RegistryScan(db);
    }
    @AfterAll void cleanup() throws Exception {
        if (!created) return;
        assertTrue(name.matches("owa_lifetime_[0-9a-f]{32}"));
        try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("drop database " + name); }
    }
    private static LifecycleSettings settings(Optional<Duration> max) {
        return new LifecycleSettings(false, Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofSeconds(60), 3,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofHours(24), 100, Optional.empty(), max);
    }
    private static final class Engine implements WorkflowEngine {
        final AtomicInteger starts = new AtomicInteger(), cancels = new AtomicInteger();
        @Override public boolean hasDefinition(DefinitionRef definition) { return true; }
        @Override public EngineLaunch prepare(DefinitionRef definition, Map<String,Object> input) {
            String id = "engine-" + UUID.randomUUID();
            return new EngineLaunch() {
                public String engineInstanceId() { return id; }
                public void start() { starts.incrementAndGet(); }
            };
        }
        @Override public Optional<ActiveInstance> activeInstance(DefinitionRef definition, String id) {
            return Optional.of(new ActiveInstance(id, EngineStatus.RUNNING));
        }
        @Override public boolean cancel(DefinitionRef definition, String id) { cancels.incrementAndGet(); return true; }
    }
    private InstanceService service(Engine engine, Optional<Duration> max) {
        return new InstanceService(store, engine, LifecycleFaults.none(), Clock.fixed(start, ZoneOffset.UTC),
                UlidGenerator.shared(), settings(max));
    }
    private StartRequest request() {
        return new StartRequest("tenant-" + UUID.randomUUID(), owner, definition, Map.of(), "key");
    }
    @Test void startPinsOnceAndAChangedSettingCannotExtendIt() throws Exception {
        var engine = new Engine(); var request = request();
        var first = service(engine, Optional.of(Duration.ofMinutes(10))).start(request);
        assertTrue(first.created()); assertEquals(1, engine.starts.get());
        var originalDeadline = start.plus(Duration.ofMinutes(10));
        assertEquals(Optional.of(originalDeadline), scan.pinnedLifetime(first.record().id().value()));
        var duplicate = service(engine, Optional.of(Duration.ofHours(3))).start(request);
        assertFalse(duplicate.created()); assertEquals(first.record().id(), duplicate.record().id());
        assertEquals(Optional.of(originalDeadline), scan.pinnedLifetime(first.record().id().value()));
        assertEquals(1, engine.starts.get());
        var after = start.plus(Duration.ofMinutes(11));
        assertTrue(scan.livePastLifetime(after, Optional.empty(), 100).stream().anyMatch(r -> r.id().equals(first.record().id().value())));
        var config = settings(Optional.empty());
        var sync = new EngineStatusSync(store, scan, config, Clock.fixed(after, ZoneOffset.UTC));
        var reconciler = new Reconciler(store, engine, sync, scan, new RepairStore(db), config, db,
                Clock.fixed(after, ZoneOffset.UTC), request.tenant()::equals);
        assertEquals(1, reconciler.reconcileOnce().count("lifetime.examined"));
        assertEquals(InstanceStatus.CANCEL_REQUESTED, store.find(request.tenant(), first.record().id()).orElseThrow().status());
        assertEquals(1, engine.cancels.get());
    }
    @Test void aStricterCurrentLimitStillAppliesAndPinsCannotBeMutated() throws Exception {
        var engine = new Engine(); var request = request();
        var first = service(engine, Optional.of(Duration.ofHours(1))).start(request);
        var now = start.plus(Duration.ofMinutes(11));
        assertTrue(scan.livePastLifetime(now, Optional.of(now.minus(Duration.ofMinutes(10))), 100)
                .stream().anyMatch(r -> r.id().equals(first.record().id().value())));
        for (String statement : List.of("update task.instance_lifetime set deadline=deadline+interval '1 hour' where instance_id=?",
                "delete from task.instance_lifetime where instance_id=?")) {
            try (var c = db.getConnection(); var q = c.prepareStatement(statement)) {
                q.setString(1, first.record().id().value()); assertThrows(SQLException.class, q::executeUpdate);
            }
        }
        try (var c = db.getConnection(); var q = c.prepareStatement("truncate task.instance_lifetime")) {
            assertThrows(SQLException.class, q::executeUpdate);
        }
        assertEquals(Optional.of(start.plus(Duration.ofHours(1))), scan.pinnedLifetime(first.record().id().value()));
        var config = settings(Optional.of(Duration.ofMinutes(10)));
        var sync = new EngineStatusSync(store, scan, config, Clock.fixed(now, ZoneOffset.UTC));
        var reconciler = new Reconciler(store, engine, sync, scan, new RepairStore(db), config, db,
                Clock.fixed(now, ZoneOffset.UTC), request.tenant()::equals);
        reconciler.recover(request.tenant(), first.record().id().value());
        assertEquals(InstanceStatus.CANCEL_REQUESTED, store.find(request.tenant(), first.record().id()).orElseThrow().status());
        assertEquals(1, engine.cancels.get());
    }
    @Test void aFractionalMicrosecondNeverRoundsTheStoredDeadlineUp() {
        var request = request(); var duration = Duration.ofSeconds(10).plusNanos(1_999);
        var first = service(new Engine(), Optional.of(duration)).start(request);
        assertEquals(Optional.of(start.plusSeconds(10).plusNanos(1_000)), scan.pinnedLifetime(first.record().id().value()));
    }

    @Test void failedAuthorityHookRollsBackTheInstanceAndItsLifetimePin() {
        var engine = new Engine(); var request = request();
        assertThrows(StoreUnavailableException.class, () -> service(engine, Optional.of(Duration.ofMinutes(10))).start(request,
                (connection, instance) -> { throw new SQLException("injected failure"); }));
        assertTrue(store.findByKey(request.tenant(), request.idempotencyKey()).isEmpty());
        assertEquals(0, engine.starts.get());
    }
    @Test void upgradingAnExistingDatabaseKeepsLegacyInstancesOnTheCurrentLimit() throws Exception {
        String legacyName = "owa_lifetime_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(legacyName.matches("owa_lifetime_[0-9a-f]{32}"));
        boolean made = false;
        try {
            try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("create database " + legacyName); made = true; }
            var legacy = source(legacyName);
            Flyway.configure().dataSource(legacy).locations("classpath:db/migration").target("77.1").load().migrate();
            var legacyStore = new JdbcInstanceStore(legacy); var request = request();
            var initial = InstanceRecord.starting(UlidGenerator.shared().next(), request, start);
            legacyStore.insertIfAbsent(initial, "test.start");
            legacyStore.replace(initial.successor(start, InstanceStatus.RUNNING, "legacy-engine", 0, null, null),
                    initial.version(), "test.running", owner);
            Flyway.configure().dataSource(legacy).locations("classpath:db/migration").target("77.2").load().migrate();
            var upgradedScan = new RegistryScan(legacy); var now = start.plus(Duration.ofMinutes(11));
            assertTrue(upgradedScan.pinnedLifetime(initial.id().value()).isEmpty());
            assertTrue(upgradedScan.livePastLifetime(now, Optional.of(now.minus(Duration.ofMinutes(10))), 100)
                    .stream().anyMatch(ref -> ref.id().equals(initial.id().value())));
        } finally {
            if (made) try (var c = admin.getConnection(); var s = c.createStatement()) { s.execute("drop database " + legacyName); }
        }
    }

    @Test void anExistingUnpinnedInstanceUsesTheCurrentSetting() throws Exception {
        var engine = new Engine(); var request = request();
        var first = service(engine, Optional.empty()).start(request);
        assertTrue(scan.pinnedLifetime(first.record().id().value()).isEmpty());
        try (var c = db.getConnection(); var q = c.prepareStatement("insert into task.instance_lifetime(instance_id,deadline) values (?,?)")) {
            q.setString(1, first.record().id().value()); q.setTimestamp(2, Timestamp.from(start.plus(Duration.ofHours(1))));
            assertEquals("23514", assertThrows(SQLException.class, q::executeUpdate).getSQLState());
        }
        var now = start.plus(Duration.ofMinutes(11));
        assertTrue(scan.livePastLifetime(now, Optional.of(now.minus(Duration.ofMinutes(10))), 100)
                .stream().anyMatch(r -> r.id().equals(first.record().id().value())));
        assertFalse(scan.livePastLifetime(now, Optional.empty(), 100)
                .stream().anyMatch(r -> r.id().equals(first.record().id().value())));
    }
}
