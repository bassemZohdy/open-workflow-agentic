# WP-109: independent owner renewals

The explicit workflow-owner coordinator previously used one scheduled worker for all local owners. One renewal
blocked on a registry row could delay every other renewal until its lock timeout, even when their rows were independent.
The coordinator now uses `min(capacity, 4)` daemon workers. Each session's fixed-delay renewal remains serialized,
and uncertain renewal still revokes that context locally rather than assuming the lease is valid.

In a PostgreSQL test, a lock holds one owner's renewal while a second owner renews and passes its live database fence.
The selected coordinator, lease, and SDK execution-boundary suite passed 23 tests. A separate negative control restored
the single worker and the new independent-renewal test failed waiting for the second renewal, as intended.

This coordinator is still an explicit candidate, not the production start or restore path. Four blocked workers can
still starve other renewals, and database connection acquisition and network waits are not bounded by the SQL timeout.
The pool does not fence in-flight external effects, establish production ownership or complete multi-replica failover.
