# WP-103 explicit allocation DATA store on proposed V78

Writer GPT; reviewer Claude. Baseline main942cde101748595397ea061a6ef3eedf90f9491e.
V78 remains in db/proposed, not Flyway. This store does not activate it, register a CDI
bean, deploy roles, supply approval, reserve model cost or enter a graph/target.

## Implemented boundary

JdbcResourceTaskAllocations implements the existing ResourceTaskAllocations DATA port.
It preflights the full registry definition and exact whole-second task wire form before
transaction/connection I/O. A configured TransactionManager is mandatory; no optional
one-argument convenience path can leave allocation inside ambient caller JTA.

The first exact canonical call retains one immutable task/profile/deadline. Reads and
repeats compare the whole scope and every task/profile field; changed approval conflicts
at the same allocation key. Only no allocation row is empty. Missing call/occurrence,
corrupt shape/fingerprints/lifetime, database/commit/JTA failure refuses rather than
becoming absence. An uncertain commit is reconciled through the SAME original identity.

Allocation locks its actual registry row and delegates actual grant ancestry/agent,
parent/narrowing and original database-clock deadline checks to proposed V78. Exact
historical repeats return original DATA even after completion/revocation/expiry; they
never renew deadline, recreate a task, reset budget or prove current permission.

Every operation suspends/resumes caller JTA and owns a short transaction. Keep original
work failure and suppress concurrent resume failure; a resume failure after success
refuses the return although the row may already be committed. Inherit configured
isolation and timeouts, including stronger SERIALIZABLE; no silent READ COMMITTED
reset or whole-call deadline is introduced. Acquisition/statement/network bounds still
need the later production composition/deployment profile.

The strict codec rejects unknown fields and numeric/text coercion. Legitimate allow-list
order is not identity, but duplicates/non-text items cannot disappear through Set decoding.
Model nanoseconds are retained; unsupported subsecond task wall time is refused before I/O.
Source provenance: only the selected old allocation store/codec/preflight files were
reworked from gpt/WP-103-execution-contract. The old redirected-schema fixture and the
rest of that stack are not merged. Actual full V77/private proposed V78 fixtures are used.

## Executed verification

Actual Java21 / PostgreSQL16.15, fresh verified UUID common DB plus one independently
owned full-migration/proposed-schema DB per store case. Common DB dropped without FORCE
after Maven exit; shared default database was not used/reset.

Both first and restored complete families pass57, zero failures/errors/skips:
contracts9 + runtime preflight/codec4 + app44 (store14 / V76 facts20 / proposal9 / default1).
The restored run additionally separates contract-corruption from occurrence-absence:
restore the valid contract and prove it reads, then remove occurrence and require refusal.

Store cases prove exact/reopen identity, conflict versus absence, six concurrent retries,
historical completion/revocation, an ACTUAL original deadline crossed on the database
clock without renewal, actual commit/lost reply/reopen, real ambient caller rollback,
unavailable source, corrupt contract/missing occurrence, current registry on new writes,
grant revocation during a real registry-row-lock wait, an actual retained legacy parent
with a distinct canonical call thread, inherited SERIALIZABLE/200ms/500ms settings, and
primary/suppressed plus success/resume refusal. No default store bean is installed.

Negative control: remove only the conflicting-retry comparison in same(). The existing
row is incorrectly returned for a changed approval; the named conflict case fails its
missing IllegalStateException assertion. Original production bytes are restored before
the complete57-case rerun. Logs: target/allocation-store-{author,conflict-negative,
restored-final}.log. Tests are DATA/fixture mechanics, not signed identity/profile approval
or a production model/graph call. Exact peer/current CI remain pending.

## Remaining gates

This is not V78 activation approval or a whole-package/acceptance closure. The migration
plan and rehearsal gates remain: source/role/default-privilege/executor denial, complete
parent/grant/expiry/cycle/narrowing negative cases, canonical original-occurrence producer,
trusted model configuration/approval, V79 holds/budget/usage and actual consumers/freshness.
The schema FK proves existence only. Never use an expired repeat/read as permission.
No legacy thread remapping, invented approval/backfill, per-node/provider fence or current
Root/async recovery proof is supplied; issues75/76 remain open.
