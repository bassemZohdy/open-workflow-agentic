# WP-102: governed asynchronous dispatch consumer

Status: implementation increment for review. A building block; it is not wired into the Flow engine path yet.

## What this adds

`GovernedAsyncDispatch` (oa-runtime, `runtime.identity`) is the governed consumer the WP-102 remaining task names: the
piece that carries the effective user's authority into one asynchronous task of a live instance without storing a bearer.

`run(engineInstanceId, audience, scopes, work)`:

1. Restores the instance authority from the registry by engine instance id. Unlike `InstanceAuthorities.restore`, an
   unknown, undelegated or unreadable instance **refuses** (it never runs without an identity).
2. Re-checks the delegation grant now (`recheck`): revoked, expired or ancestor-ended grants refuse with `REVOKED`; an
   undecidable check refuses with `UNAVAILABLE`.
3. Takes the process-private subject credential from a `Credentials` port; none means re-authorization (`EXPIRED`), not
   an ambient identity.
4. Exchanges for a fresh token for exactly one audience and scope set. `GRANT_INACTIVE` marks the instance for
   re-authorization (audited first by the existing `require`), other failures refuse.
5. Runs `work` inside `AuthorityScope` (identifiers only). The issued token exists only as the callback argument.
6. After the blocking work, checks the grant and the token expiry again; a revocation or expiry during the work
   withholds the result. A business failure of the work propagates unchanged.

14 unit tests cover each path without a database, including: fresh exchange per task after a wait, no run without
identity, revocation and token expiry **during** blocking work, interrupt handling, malformed audience/scopes never
reaching the exchanger, an audit-write failure not marking the instance, and the bearer never appearing in the scope.

## Engine enforcement (this increment)

`GovernedTaskGuard` is a `WorkflowExecutionCompletableListener` that makes the registry-only `CurrentAuthority` check at
every task start, resume and completion and fails the task by throwing `AuthorityLostException`. Probed against the
real Flow engine (SDK 7.35.3): a thrown exception from a *completable* listener fails the task and the instance; a
failed future is ignored, and a plain synchronous `WorkflowExecutionListener` that throws is **swallowed** (the workflow
completes), so only the completable form enforces. Eight tests run real workflows: revocation between tasks (next task
never runs), revocation during the last task (output withheld), unknown/undelegated instances (refused or allowed per
policy), unreadable registry (always refused), undecidable check.

`IdentityProducers` produces it as a CDI listener, switched by `agentic.identity.task-guard`: `off` (default, no
behaviour change, a no-op listener), `allow-ungoverned`, or `require-delegated`. Whether Quarkus Flow's own listener
discovery wires this bean into live applications is verified only by CI and still needs live evidence.

## Not done (still open WP-102 gates)

- Every async task going through `run` (token exchange per tool call; the guard only enforces authority currency), and
  a production `Credentials` that holds the user's subject token privately in memory only.
- Evidence with real live tasks and a restart against PostgreSQL and the real provider: current effective user, acting
  workload, tenant, grant and correlation after restart, and denial after revocation/expiry during blocking work.
- Reciprocal review of the exact head before WP-102 changes status.

Review request: confirm the subject-credential source design (process-private, absent means re-authorization) before
the engine wiring is written against `Credentials`.
