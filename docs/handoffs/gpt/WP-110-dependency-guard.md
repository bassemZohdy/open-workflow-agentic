# WP-110: DependencyGuard, the shared resilience primitive

Status: implementation increment for review. A building block; it is applied to no dependency boundary yet.

## What this adds

`DependencyGuard` (oa-runtime, `runtime.resilience`) protects one dependency boundary with three mechanisms, none of
which adds a thread, queue or timer:

- **Load shedding** - at most `maxConcurrent` calls run at once; the next is rejected immediately with `OVERLOADED`.
  Nothing waits in a queue, so overload cannot pile up unbounded work.
- **Circuit breaker** - after `failureThreshold` consecutive failures the circuit opens; calls are rejected with
  `CIRCUIT_OPEN` without touching the dependency for `openFor`. Then exactly one probe is allowed (half-open): success
  closes the circuit, failure reopens it for another full period. A probe shed by the bulkhead cannot leave the circuit
  stuck half-open.
- **Retry budget** - `callWithRetry` retries a failure the caller marks retriable, with a fixed backoff, only while
  `retryBudget` retries per `retryWindow` last, so retries cannot multiply a failing dependency. Rejections are never
  retried; the last failure is thrown unchanged; an interrupt while pausing stops retrying and keeps the flag.

A rejected call is neither a success nor a failure. The dependency's own failure always propagates unchanged and is only
counted (`stats()`). Clock and sleeper are injectable, so every transition is tested deterministically. 16 DB-free tests
cover shedding, permit release on failure, the full breaker cycle with a single probe, the budget and its window,
interrupts, counters and validation.

## Not an effect guard

Use it only for operations that are safe to repeat or to skip (reads, token or JWKS fetches, policy lookups). A call
that changes an external system must NOT be retried here; that is the effect ledger's job (write-ahead intent, stable
key, unknown-outcome reconciliation). The WP-110 requirement that committed work is not lost or duplicated is met by
keeping effects out of this path, not by this class.

## Still open for WP-110

- Applying a guard per dependency boundary (token endpoint, JWKS, policy decision point, model provider, database,
  broker) with settings per boundary, and rate limits on the inbound API.
- Overload and outage injection against the real application and the automated T17 proof.
- Exposing `stats()` and the breaker state as metrics (WP-112).
