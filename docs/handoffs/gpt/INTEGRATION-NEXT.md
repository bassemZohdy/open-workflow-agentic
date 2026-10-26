# Integration queue: next actions (2026-10-26)

Current implementation baseline: main `9734347747092b1297136fac168624ae27b61df8`.
No open PR remains; the current-profile consumer and startup proof are integrated. Counts stay11 completed/12 partial/30 not started; acceptance
stays3/39. These increments do not close WP-108 or authorize graph entry.

## Integrated prerequisites and evidence

- Membership55, fingerprint57, central append-once58, attribution60 and node
  interceptor61 are integrated; their historical handoffs retain the exact evidence.
- CI62/63 merged as4f3032a after exact Claude PASS1396bd4/6403683125 and full
  40236424166. Main/tracking40237056066/40237188334 and the actual Linux scoped
  reactor passed. Markdown-only PRs skip Maven; tests/unknown/tracking sources use
  full checks. Renames retain both paths and missing scope output falls back safely.
- Audit fixture64 merged as63291fa after exact GPT PASS6610bf1/6403925367 and
  full40237862668. Independent31 tests pass before/with private V75; both missing
  parent-guard controls fail as intended, then restored append-only5 pass.
- V75/PR65 merged as2beaeb4 after Claude PASScd15aca/6404200386 and full40239627410.
  Main40240374370/tracking40240551870 passed. The fresh-role grant procedure is
  tested but supplies no credentials, data-source or CDI adoption.
- Execution contract66 merged as1773a00 after Claude PASSde853bb/6404473197 and
  full40241496066; main40242016592/tracking40242658627 passed. Contracts200,
  serializer1 and default admission refusal1 pass. Data entries/outcomes/fence
  signatures are not a callback, backend or current-authority grant.
- Checkpoint profile67 merged asb02875f after Claude PASSa01b8e1/6405038545 and
  full40245351968. GPT inspected actual artifact12617854320:170 XML suites,
  1183 executed cases, zero failures/errors/skips; actual schema10/24 observations,
  migration/real-saver/grants4, default admission refusal1 and required method sets.
  CRC/paths/archive digest/migration+vendor hashes and exact tested tree checked.
  See [retained inspection](evidence/WP-108-checkpoint-profile/current-ci.json).
  Postmerge40246336207 failed IdentityRecoveryIT (reauthorized y stayed WAITING).
  Main40246884344 later passed; this is an unresolved intermittent defect. See
  [failure metadata](evidence/WP-108-checkpoint-profile/postmerge-recovery-failure.json).
  Diagnostics68 merged as2bba3a5 after exact Claude PASS1a1adf0 and full40248132488;
  process logs are retained. Claude69 merged asc8dabf6 after exact GPT PASS5eb7d1a
  and full40248398566 plus real-Keycloak40248398613 standard3/delegation8.
  One initial Linux run plus20 recorded repeats pass; the original defect stays open.
  See [local repeat evidence](evidence/WP-108-checkpoint-profile/identity-recovery-local-repeats.json).
  V75_1 preserves compatible private history,
  refuses unsupported/partial/active stores and adds the observed collation gap
  regression. Runtime DDL removal and data-source adoption are not activated.

- Shared checkpoint mutation fixtures70 merged as79da38e after exact Claude
  PASSb9db578/6405603074 and full40249904765; actual schema10/24 observations,
  recovery1/default-admission1 passed and migration hash is unchanged.
  Latest main40250625309 passed. See [consumer support](WP-108-checkpoint-consumer-support.md).

- Consumer71 merged as694dc326 after GPT PASS6e02b0e/6406283085 and full
  40253621994; actual172 suites/1220 cases pass. Real-Keycloak40253622052
  standard3/delegation8 and effect-recovery1 each are non-skipped. Ignored
  namespace and function-shadow findings are fixed; independent Java21/PG16
  focused68 pass. Startup proof72 merged as97343477 after Claude
  PASS031beee/6406298991 and full40255084555:173 suites/1224 cases pass, including
  four actual packaged startup scenarios. Postmerge consumer core/provider
  40254990550/40254990868 and startup40256568737 pass. Source trees/archive hashes and methods
  verified. See [integration evidence](WP-108-checkpoint-consumer-integration.md).

## Next ordered gate: V76 entry/backend and composite live fence

Use the older V76 proposal as intake only. Its cloned-metadata test is not an
actual ordered migration, backend or callback proof. Reconcile against current
immutable call/binding schema and the actual pinned checkpoint consumer first.

1. One irreversible entry per original call key, not per credential/owner/epoch.
   Keep immutable facts and exact target/run/checkpoint outcome references after
   cancellation or expiry. Use real migration/role/constraint/race tests.
2. Only the newly acknowledged fresh entry callback may run work. Duplicate or
   uncertain commits, new credentials/epochs, empty history and UNKNOWN refuse
   another graph run. Prove actual commit-reply loss and process/owner races.
3. Compose current registry/full owner/definition/task/approved target/execution
   ownership with Claude's live identity/grant/scope/PDP hook after blocking I/O,
   immediately before entry, each model/tool node and output release.
4. Mint an opaque nonserializable admission witness in the verified path; the
   public Principal and stored entry/scope are data. Pass private async control
   context explicitly outside graph state/config/checkpoint metadata; no ambient
   ThreadLocal. Invalidate cancellation promptly but retire contexts/worker slots
   only after actual termination. Bound all active/pending live-check work.
5. Obtain expected acting workload from a server-trusted pinned definition/task/
   deployment binding, never the token/grant/request/owner. Missing mapping and
   approved-model context refuse. No default admission allowance is activated.

Claude is preparing the minimal owned identity/PEP hook contract. GPT owns V76
schema/backend, registry/lease/target composition and actual Flow dispatch. Review
that handoff before implementation; keep scopes and ownership explicit. The
original intermittent identity recovery failure remains open, separately tracked.

## Following ordered gates

The owned consumer/schema boundary is merged. Sequence reserved V76 against the
actual pinned store as above. V77 producer/delivery, V78 allocation, V79 model reservations and
V80 manual decisions remain later preparations, not active main features.

Canonical original scope/full owner, one irreversible fresh entry acknowledgement,
current caller/PDP/task/target/lease checks after blocking I/O, mandatory central
receipt before dispatch, actual Flow/output and UNKNOWN/recovery remain gates.
A constructed entry, new epoch/credential, empty checkpoint store or stored receipt
cannot permit another graph run or private output release.

Existing WP112 and WP605 preparation stays parked behind this integration queue.
No package is promoted merely because preparation or a component test exists.
