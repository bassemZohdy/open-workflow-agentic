# Integration queue: next actions (2026-10-27)

Current implementation baseline: main `4b747001b5257ca9abc0ddad5bc112693ae97465`.
Audit delivery87 and controlled model88 are merged after exact peer reviews and independently inspected current CI.
See evidence/WP-108-audit-delivery/current-ci.json (1395 tests) and evidence/WP-103-running-model-boundary/current-ci.json (1409 tests).
These runs precede combination; combined-main verification is pending. Neither installs production authority or an approved model context.
Allocation DATA contracts89 merged4b74700 after Claude exact41ba16c PASS/independent209 and inspected1427 current CI. That run tests delivery/model/contracts together; actual postmerge remains separate. Next independent recovery diagnostics do not claim a fix for75.
Next: [staged allocation migration](WP-103-allocation-migration-plan.md), preserving historical unallocated entries and reserved V78..V80.
Counts remain11 completed/12 partial/30 not started; acceptance3/39. Issues75/76 remain open.

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
  regression. Runtime DDL removal was pending at that increment (consumer71 below subsequently supplied it); dedicated credential/data-source adoption remains unactivated.

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

## Current merged entry components and next composition gate

- Admission73 merged asa87fc292: private verified admission witness and per-call close. Full1242 plus real-provider CI
  passed; historical scope/entry data is never current permission.
- V76 facts74 merged as561c691 after exact Claude PASS3f37b3b/6419457964, independent60 and full1262.
  [Actual CI and failed-probe evidence](evidence/WP-108-v76-facts/current-ci.json) retains the corrected parent guards.
- Live identity77 merged as53eb019 after exact GPT PASS7e2a9fc/6419940971, independent53/final11,
  full1273 and standard3/delegation8. [Evidence](evidence/WP-102-live-identity/current-ci.json).
- Internal callback gate78 merged asaf9d7e2 after exact Claude PASS04c55a1/6420317407, independent67 and full1291.
  Only fresh INSERT plus acknowledged commit/close/ambient resume reaches its callback. Duplicate/readback/unknown
  never do; cancelled running synchronous workers retain capacity until actual termination. [Evidence](evidence/WP-108-execution-gate/current-ci.json).

Next, pair the gate binding with the SAME private admitted credential (full record, not merely equal entry/hash), then
build the bounded concrete composite and trusted production composition root. #79 supplies the pairing-aware identity
check and a historical derived binding fingerprint; GPT supplies canonical DATA lookup. Pairing79 is reviewed and merged; lookup80 still requires its current full CI gate. No CDI admission/model/graph default is enabled.

The root must source the expected workload, approved target and task from server-trusted pinned facts; missing mapping
refuses. Compose current registry/full owner/definition/actual task/target/lease with current identity/grant/scope/PDP,
mandatory central audit and rechecks after blocking I/O. It must invoke the pairing-aware check on the exact same binding,
restrict gate construction to that root and configure bounded JDBC lock/statement/network timeouts. Public data values
and arbitrary fence lambdas cannot become an endpoint/workflow capability.

Actual Flow/agent dispatch, per-node and output release, exact immutable terminal-checkpoint association, private async
propagation and process/owner/unknown-outcome recovery remain unproven. No handles, credentials or authority flags belong
in graph state, RunnableConfig metadata, checkpoints or ThreadLocal. Use an explicit private control context and retire it
only after actual work termination. The full [failure matrix](WP-108-backend-entry-prep.md) remains the acceptance target;
component callback tests are not graph or package closure.

## Following ordered gates

The checkpoint consumer/schema boundary and data-only V76 are merged; production composition is still absent.
V77 DATA and the opt-in producer86 are merged. Delivery87 is unmerged; V78 allocation, V79 model reservations and V80 manual decisions remain
later preparations, not active main features.

Canonical original scope/full owner, one irreversible fresh entry acknowledgement,
current caller/PDP/task/target/lease checks after blocking I/O, mandatory central
receipt before dispatch, actual Flow/output and UNKNOWN/recovery remain gates.
A constructed entry, new epoch/credential, empty checkpoint store or stored receipt
cannot permit another graph run or private output release.

Existing WP112 and WP605 preparation stays parked behind this integration queue.
No package is promoted merely because preparation or a component test exists.

### Latest implementation evidence

- Grant-expiry81: [exact review/current CI](evidence/WP-102-grant-expiry-after-io/current-ci.json),
  post-blocking expiry only, not atomic revocation.
- Governed Running82: [exact review/current CI](evidence/WP-108-governed-running-control/current-ci.json),
  [combined main1329](evidence/WP-108-governed-running-control/combined-main.json). Construction rule covers app-runtime
  and its production dependencies; any additional app depending on runtime needs its own guard. Review the future
  root and its allowlist together. Static-call rules are not reflection security boundaries.
- Zero-budget83: [exact review/current CI](evidence/WP-103-zero-model-budget/current-ci.json),
  [combined main1331](evidence/WP-103-zero-model-budget/combined-main.json). Positive counters are not reservations;
  default approved context is still empty.

- Audit child84: [exact/current CI](evidence/WP-108-audit-child-fixtures/current-ci.json),
  [combined main1338](evidence/WP-108-audit-child-fixtures/combined-main.json); child errors cannot mask parent guards.
- V77 facts85: [exact/current CI](evidence/WP-108-v77-audit-facts/current-ci.json),
  [combined main1365](evidence/WP-108-v77-audit-facts/combined-main.json). No invented legacy provenance, producer,
  causal relay or receipt/current-authority permission follows. Source writer/relay adoption remains reviewed work.

- Atomic producer86: [exact/current CI](evidence/WP-108-atomic-audit-producer/current-ci.json),
  [combined main1386](evidence/WP-108-atomic-audit-producer/combined-main.json),
  [earlier66571db proof](evidence/WP-108-atomic-audit-producer/prior-head.json).
  Known fresh INSERT only establishes provenance; legacy data is not upgraded, source pages are not full history,
  and registry checks before retries are structural. Default producer path remains absent; no target follows DATA.
