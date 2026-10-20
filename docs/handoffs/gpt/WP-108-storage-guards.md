# WP-108 additive effect integrity guards

Owner: GPT. Reviewer: Claude. Preparation after EffectScope3d16c17; no second
active implementation PR while #46 remains in review. WP-108 remains partial.

V70 is allocated centrally by GPT for effect invariants. V63 and every applied
migration stay unchanged. The new ledger trigger requires new rows to start as
unattempted INTENDED, preserves the exact logical key/target/request digest/
purpose/creation time/replay deadline, permits only the existing EffectState
moves, and forbids clearing a dispatch attempt or attaching one after an
outcome. Repeated states cannot rewrite recorded detail/time. Intent deletion,
transition-event mutation/deletion and compensation-link mutation/deletion are
refused. The existing trusted API, stable keys and compensation behavior stay
the same, including FAILED -> COMPENSATED and explicit duplicate outcomes.

These triggers defend ordinary storage writes, not a database administrator
who disables triggers, TRUNCATE, table-owner privileges, or compromised database
roles. They are not ownership/policy checks, central hash-chain audit, a
verified manual decision API or an automatic journal for arbitrary direct SQL.
Legal raw SQL transitions are still trusted internal operations. No credential,
public endpoint, registered adapter or production Flow dispatch is introduced.
Manual retention/purge needs a separately reviewed privileged archival design;
no historical intent/event/link is removed by this component.

Five new PostgreSQL cases compile: complete state-pair matrix (including final
and repeated states), immutable identity/replay binding, irreversible attempts
and outcome evidence, refusal of forged initial outcomes/attempts, and retained
intent/event/compensation evidence. The original component and process-kill T03
assertions remain. Local app reactor test-compile succeeds. Exact199cf5b
workflow39374918657 SUCCESS BOTH Linux lanes; complete job logs inspected:
EffectLedgerGuardTest5, existing DurableEffectsTest12 and JdbcEffectLedgerTest4,
standard3/native8, all zero failures/errors/skips; both strict evidence checkers
pass. The new SQL guards are now executed, not compile-only. This matrix runs
the selected provider IT, so it does not re-execute process-kill T03; final
implementation PR full CI must retain that existing regression gate. Later
tracing changes have their own new execution/review requirement.

The authority/PEP/manual/central-audit request remains WP-108-authority.md.
Governed adapters, actual Flow re-execution, old-owner/partition windows, full
compensation/manual resolution and distributed tracing remain acceptance gates.

Current preparation product4325993 now passed BOTH39381656269 with V70 guards5,
original effect service12, actual CDI tracing3 AND the original separate-worker
EffectCrashRecoveryIT1, zero failures/errors/skips; both full logs and strict
provider/process evidence checks inspected. This re-executes the original core
charge/refund abrupt-process recovery under V70 on merged main48. It does not
prove actual governed Flow/owner/partition recovery or remove the gates above.
