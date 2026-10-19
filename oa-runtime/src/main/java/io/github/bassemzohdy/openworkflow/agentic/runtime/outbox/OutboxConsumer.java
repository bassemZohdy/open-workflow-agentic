package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A receiver of outbox events (WP-105b).
 *
 * <p><b>Contract.</b> {@link #apply} runs on the relay's connection, inside the transaction that marks the event
 * published. Its writes commit together with the mark, so a published event has been applied and an unpublished one
 * has not been, unless the consumer wrote outside the database, which is not allowed here.
 *
 * <p><b>Delivery is at least once.</b> After a crash, or when an operator re-queues a row, the same event comes again.
 * An implementation must give the same result when it sees an event twice and must ignore an event it has already
 * applied. It must not depend on arriving once.
 *
 * <p><b>Failure.</b> A thrown exception rolls back this consumer's writes for this event only (to a savepoint) and
 * counts a failed attempt. The row is retried with backoff and marked failed after the configured maximum.
 */
public interface OutboxConsumer {

    /** A stable name for logs and failure records. */
    String name();

    /**
     * Applies one event.
     *
     * @throws SQLException     for a database failure; the event is retried
     * @throws RuntimeException for a rule the consumer cannot apply yet; the event is retried
     */
    void apply(Connection connection, OutboxEvent event) throws SQLException;
}
