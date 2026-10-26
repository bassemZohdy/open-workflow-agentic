# WP-103 proposed allocation denial matrix

This increment exercises existing proposed V78 constraints on private PostgreSQL16
databases migrated through the actual current V77. It changes tests only. V78 stays
outside Flyway; no writer role, approved configuration source, model budget, execution
consumer or production authority is introduced.

`ResourceTaskAllocationDenialTest` composes the existing migration fixture without
inheriting its test methods. Each case creates and verifies its own UUID database,
applies the proposal in an owned transaction, and drops the database without FORCE.
All SQL failures assert the intended SQL state and guard message and zero allocation
and execution rows. The positive retained-parent case also proves that the guard is
not simply refusing every allocation.

The matrix covers changed registry owner kind/id, engine, definition/version and
cancellation; retained-parent owner kind/id, tenant, terminal state, legacy thread,
future creation, elapsed lifetime and embedded contract attribution; widened tool/data
lists, iteration/cost/wall limits and lowered risk; actual chosen/issuing grant
revocation, a different issuing chain, a different profile agent, actual database-clock
grant expiry, and an elapsed registry wait deadline. Parent mutations are explicit
owned fixture setup, not evidence that deployed runtime writers can edit parent facts.

The cycle case exercises the existing real `DelegationGrants` producer and applied
V60 immutable-parent guard: returning to an existing agent is refused and reparenting
an existing root is refused. It does not claim that V78 independently reconstructs
or repairs arbitrary cyclic rows produced with privileged constraint bypass.

## Verification

Actual Temurin21.0.12.1 / Maven3.9.11 / PostgreSQL16.15 restored full family:85 tests, zero failures/errors/skips (contracts9 + runtime4 + app72: denial28, store14, V76 facts20, proposal9, default wiring1). Exact peer and current required CI remain pending. The verified UUID common database was dropped without FORCE after Maven exit.
The first 21-case matrix passed. Expanding it initially exposed one invalid test
fixture (a SERVICE model-profile agent refused by the shared constructor); that
fixture was corrected to a valid AGENT with a different id before final verification.

The negative control removes only the proposed SQL parent narrowing block. The new
five-field narrowing tests failed5/5 because widened allocations were accepted (missing SQLException assertions).
Original SQL bytes were restored and byte-compared before/after the passing full family. Logs: target/allocation-denials-{narrowing-negative,restored-final}.log; no modified
proposal or applied migration is committed.

## Remaining gates

These are DATA denial checks, not proof of signed model approval, live execution
permission, a production-role boundary, parent checkpoint remapping or an invoked
target. Actual source/role/default privileges/executor denial, the production
occurrence producer and retained parent association, V79 aggregate holds/usage,
current task/lease/PDP/audit, per-node/provider fences, actual Flow terminal behavior,
bounded operational I/O and physical async recovery remain separate gates. A valid
FK or historical allocation read never authorizes a call. No work package or
acceptance test is closed; issues75/76 remain open.
