# ADR-0011: Use one PostgreSQL database for all durable state

**Status:** Accepted for the proof of concept  
**Date:** 2026-10-18

## Context

Workflow instances, agent checkpoints and scheduled tasks all need crash-safe persistence (M03, M04).

## Decision

Use one PostgreSQL database: `quarkus-flow-jpa` for workflow state, LangGraph4j `PostgresSaver` for graph checkpoints, db-scheduler for timers. Schema for the scheduler comes from Flyway; the other modules create their own tables.

## Consequences

- One backup, restore and failover story; simple local setup.
- Components share a failure domain. Splitting databases later is a configuration change, not a redesign.
- Hibernate `database.generation=update` is used for the bootstrap only and must be replaced by migrations before production.

## Resource-call attribution preparation (2026-10-20)

The reviewed #49 ResourceCallBindings port uses this same database. Proposed V73
(after V70/V71/V72) retains the invocation contract and issuer/jti mapping in two
immutable task tables, committing both in a short JDBC transaction independent of
ambient JTA. Registry full-owner/definition/engine checks and exact V65 metadata
references prevent rebinding or premature deletion. Signed timestamps must fit
PostgreSQL microseconds and years0001..9999; unsupported values are refused before
I/O, never rounded. Current JWT producers use whole seconds.

An exact historical repeat/read is metadata even after credential expiry or run
completion. A new mapping requires a RUNNING/WAITING registry row. Neither case
returns authority, replay permission, execution ownership or a result. The
producer must still complete current authority, registered SDK/task/target/request
validation and mandatory audit before bearer release. No bearer/raw body is
persisted, and this increment activates no dispatch or HTTP endpoint. Commit or
JTA-resume uncertainty refuses return; read back the SAME identity. Multiple
credentials retain one contract but do not authorize multiple graph executions.

Append retention deliberately has no generic delete path: coordinated archival,
quotas and backup/restore remain open gates. SQL triggers do not claim protection
from a database administrator disabling constraints, or automatic central audit
for direct SQL. The implementation and twelve database cases require fresh Linux
execution and peer review before integration; package/acceptance status stays partial.

Productc63130e subsequently passed BOTH Linux39387463567 lanes: all twelve new
actual PostgreSQL cases plus existing issuing-migration5/group11, zero failures/
errors/skips. GPT inspected both finished-job logs. This proves that component
scope, not final-head peer approval, whole governed execution or package closure.
