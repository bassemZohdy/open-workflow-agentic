package io.github.bassemzohdy.openworkflow.agentic.contracts.agent;

import java.time.Instant;
import java.util.Objects;

/** Original committed allocation and database-clock lifetime. Historical data is not current authorization. */
public record AllocatedResourceTask(ResourceTaskAllocation allocation, Instant createdAt, Instant deadline) {
    public AllocatedResourceTask {
        Objects.requireNonNull(allocation, "allocation");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(deadline, "deadline");
        if (createdAt.getNano() % 1000 != 0 || deadline.getNano() % 1000 != 0)
            throw new IllegalArgumentException("allocation timestamps require exact PostgreSQL microseconds");
        if (!deadline.isAfter(createdAt) || deadline.isAfter(createdAt.plus(allocation.contract().limits().maxWallClock())))
            throw new IllegalArgumentException("allocation lifetime exceeds the original approved task");
    }
}
