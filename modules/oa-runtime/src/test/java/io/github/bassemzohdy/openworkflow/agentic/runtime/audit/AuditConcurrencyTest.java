package io.github.bassemzohdy.openworkflow.agentic.runtime.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditEvent;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.AuditHash;
import io.github.bassemzohdy.openworkflow.agentic.contracts.governance.ChainVerification;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Many appenders on one chain: the advisory lock must give a gap-free, correctly linked chain (WP-111). */
class AuditConcurrencyTest {

    private static final int APPENDERS = 20;
    private static final int EVENTS_EACH = 10;

    @Test
    void twentyConcurrentAppendersProduceAGapFreeLinkedChain() throws Exception {
        AuditTrail trail = new AuditTrail(AuditTestDatabase.dataSource());
        String chain = AuditTestDatabase.newChain();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(APPENDERS);
        try {
            List<Future<?>> appenders = new ArrayList<>();
            for (int a = 0; a < APPENDERS; a++) {
                int appender = a;
                appenders.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < EVENTS_EACH; i++) {
                        trail.append(AuditTrailTest.draft(chain, "appender." + appender));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> appender : appenders) {
                appender.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int expected = APPENDERS * EVENTS_EACH;
        List<AuditEvent> events = AuditTrailTest.readChain(chain);
        assertEquals(expected, events.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1L, events.get(i).sequence(), "sequence must be gap-free");
        }
        ChainVerification verification = AuditHash.verifyChain(events, events.get(expected - 1).hash());
        assertTrue(verification.valid(), verification.reason());
        assertEquals(expected, new HashSet<>(events.stream().map(e -> e.id().value()).toList()).size());
        assertEquals(expected, headLogRows(chain));
    }

    private static int headLogRows(String chain) throws SQLException {
        try (Connection connection = AuditTestDatabase.connection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("select count(*) from audit.head_log where chain_id = '" + chain + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
