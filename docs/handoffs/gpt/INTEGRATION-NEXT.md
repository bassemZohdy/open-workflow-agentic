# Integration queue: next actions (2026-10-26)

Current implementation baseline: main `b02875f6d00f28d2d837505b56160db70c2dec48`.
Diagnostic PR69 is open; recovery investigation precedes the consumer. Counts stay11 completed/12 partial/30 not started; acceptance
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
  process logs are retained. Claude69 adds unchanged-assertion timeout diagnostics.
  Linux repeat verification is underway; runtime consumer work is paused.
  V75_1 preserves compatible private history,
  refuses unsupported/partial/active stores and adds the observed collation gap
  regression. Runtime DDL removal and data-source adoption are not activated.

## Following recovery investigation: Claude stops runtime checkpoint DDL

Follow [the exact handoff](WP-108-checkpoint-profile-activation.md). Its schema
prerequisite is merged. Use a separate Claude branch/worktree from current main.

1. Stop ResearchAgent.createTables(true). Do not call Flyway or execute adoption/
   creation DDL from the agent as a workaround. GPT owns migrations/configuration.
2. Add read-only verification of the actual configured store/current profile before
   graph/provider work, including startup and an existing migration history. Missing,
   incompatible or unsupported/shadowed namespaces must refuse; no repair, fallback,
   fake empty store or private-state deserialization. Request any shared lifecycle
   or configuration interface from GPT through a concrete owned handoff.
3. Prove migration-first startup/class-order correctness and refusal before graph/
   provider effects. Preserve the default unsatisfied legacy admission port.
4. Keep dedicated credentials/data-source/role deployment as the reviewed consumer/
   WP-304 boundary. A schema match alone is neither least privilege nor authority.

GPT independently reviews the exact head, verifies the relevant tests, inspects
current required CI and serializes integration after material findings are resolved.
No second GPT feature family is started while this prerequisite is integrated.

## Following ordered gates

After the owned consumer/schema boundary is reviewed and merged, sequence the
reserved V76 execution-fact/backend and composite live fence against the actual
pinned store. V77 producer/delivery, V78 allocation, V79 model reservations and
V80 manual decisions remain later preparations, not active main features.

Canonical original scope/full owner, one irreversible fresh entry acknowledgement,
current caller/PDP/task/target/lease checks after blocking I/O, mandatory central
receipt before dispatch, actual Flow/output and UNKNOWN/recovery remain gates.
A constructed entry, new epoch/credential, empty checkpoint store or stored receipt
cannot permit another graph run or private output release.

Existing WP112 and WP605 preparation stays parked behind this integration queue.
No package is promoted merely because preparation or a component test exists.
