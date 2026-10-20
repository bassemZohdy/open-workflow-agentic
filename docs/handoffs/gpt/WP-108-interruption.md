# WP-108: interrupt checks around durable effect dispatch and return

Owner GPT; reviewer Claude. Opt-in DurableEffects boundary only; no Flow router,
public manual endpoint, identity/PEP or registered adapter is activated here.
Current implementation PR #51; exact current-head CI and Claude review are pending.

## Finding and resulting behavior

The original core dispatched an unattempted effect even if the caller thread
was already interrupted or the flag arrived during claim/intent/attempt I/O.
Recovery avoided a resend for an interrupted UNKNOWN lookup, but still returned
an INTENDED value; known outcomes could return progress after interrupted I/O.
There was also no final check after advisory-claim release or tracing cleanup.

The service now checks the observed thread flag before adapter metadata/store
entry, after claim/intent preparation, immediately before execute/lookup calls,
and after later outcome/manual/read work. Compensation does the same around
original/refund reads and preparation. A final PUBLIC boundary check runs after
the entire ledger-claim and optional tracing cleanup returns. The flag is never
cleared by production code. Interruption throws standard CancellationException;
semaphore/claim cleanup still runs. No new target call or normal continuation
is released after an observed flag.

Committed intent/attempt/link facts remain. Cancellation does not mark an effect
FAILED or prove that a target did nothing. A CONFIRMED/FAILED reply already
observed may still be recorded as historical fact before refusing progress;
failure of that write leaves the retained INTENDED record for reconciliation.
No interrupted thread is silently cleared to force a database write. In
particular, a confirmed refund can remain linked while the original still
needs its COMPENSATED transition; a fresh authorized recovery completes it
without creating a different refund key. Recovery must consider retained
INTENDED records, not assume every interrupted write reached a manual queue.

These are cooperative THREAD checks only. An SDK future cancellation may not
interrupt a running worker. This does not provide registry cancellation,
distributed owner/epoch, deadline, current signed authority or partition fencing.
Those full Flow/adapter gates and the producer requests in WP-108-authority.md
remain mandatory. Historical facts/metrics/spans are not workflow advancement.

## Evidence and pending verification

Six plain deterministic cases exercise zero JDBC/adapter-metadata on entry
interruption, claim/intent/attempt boundaries, UNKNOWN lookup with no resend,
retained known facts, late claim/tracing cleanup, and more than the four-slot
worker budget without leaking permits. All preserve the interrupted flag;
tests alone clear it in finally to avoid contaminating the JUnit worker.

Negative control: temporarily restoring the exact original DurableEffects from
HEAD while keeping the initial five new tests caused FIVE failures, zero errors/
skips; final source was restored byte-for-byte in a shell finally. The sixth
late-cleanup case was added afterward. No mutation/probe file is committed.

Final selected application-reactor build passes39 plain cases (contracts6/runtime33,
including interruption6), zero failures/errors/skips; all application tests compile.

After normal mergef311fa9 of reviewed main49/30e81c3, the selected application
reactor build also includes ResourceCallScopeTest:45 total plain cases
(contracts12/runtime33), zero failures/errors/skips. Tools76/generated/diff
checks pass; source histories are preserved. New combined-head SQL/full/
provider/process CI and exact peer review remain required.
The four new ACTUAL PostgreSQL cases compile but still require new-head Linux
execution: interrupt after real intent commit, after real attempt commit, during
lookup and after compensation intent/link commit. Each later uses a new physical
PGSimpleDataSource connection to prove claims were released rather than reentered
through the same pooled session, and reconciles the original charge/refund key.
The target is an explicit external-service double; no real provider effect is claimed.

Earlier4325993/BOTH39381656269 executed the original core process test and SQL/
tracing cases BEFORE this change. Those successes do not prove the interruption
fix. New current-head full/provider/process CI and exact Claude review remain
required; original process T03 assertions are unchanged. No acceptance status
or package completion is promoted by these component checks.
