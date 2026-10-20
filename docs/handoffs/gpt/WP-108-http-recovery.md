# WP-108 actual HTTP and process recovery preparation

Owner/author verification: GPT. Consumer/reviewer: Claude. Status: preparation verified;
no second implementation PR or production activation. Sole GPT PR #55 stays
frozen ea56f3319d0e896d54703d9f4d857252906ed2a4 pending exact Claude review.

## Scope and protocol

Seven Failsafe cases exercise the existing explicit five-argument AuditedEffects
boundary over a real loopback JDK HTTP client/server. The server runs in a
separate owned JVM, uses an ephemeral port, and commits a keyed business operation
and account delta in one PostgreSQL transaction before replying. Request records
count physical POST/GET observations. Same-key/request conflict checks prevent
the fixture from quietly changing an operation. Charge and refund commands keep
their original ef1 key/request fingerprint and stable logical target across server
ports/restarts. Compensation has a separate key and retains its original parent.
GET is a read-only authoritative business lookup (request observations are test
instrumentation); missing business history yields UNKNOWN. Replay window is ZERO.
No timer/resume epoch/new occurrence can turn an uncertain call into another POST.

This is a TEST-ONLY target/transport, not an approved production HTTP adapter,
identity verifier, target registry, policy implementation or no-retry guarantee
for arbitrary HTTP stacks. Assertions check actual accepted requests and business
commits. The private composite authority fence is explicitly a protocol spy;
the actual SQL registry/current-status checks in AuditedEffects remain real.

Central receipts remain explicitly TWO-COMMIT DATA fixtures: the actual AuditTrail
append commits, then V75 source_binding commits separately. Successful readback
checks the original source/draft/receipt; it cannot establish the missing atomic
appendOnce consumer, one central head, chain authenticity or central commit-loss
semantics. Source/receipt tuples are history, never dispatch permission.

## Seven execution gates

1. Audit dependency refusal before dispatch: zero actual POST/GET/business commits.
2. Registry closes inside the blocking audit callback: zero HTTP requests after
   that callback; retained intent remains unresolved.
3. Target JVM halts after charge COMMIT and before response: original INTENDED,
   exactly one charge/POST; a fresh server/component confirms with one GET and
   preserves the original source history prefix.
4. Target JVM halts after refund COMMIT: original remains CONFIRMED and undo
   INTENDED until a fresh server/component GET confirms undo; only then original
   becomes COMPENSATED. Exactly one charge and one refund POST/business operation.
5. Target commits and blocks response; registry closes; released real response
   still retains and audits CONFIRMED but refuses the private result. Cached reads
   under that closed registry also refuse without another HTTP request.
6. Client JVM halts after receiving actual HTTP success but before returning the
   target Reply to the durable core. A fresh client JVM reopens the original key,
   confirms by GET, and never repeats the POST/business operation.
7. Target publishes a post-COMMIT marker before the actual JDK HTTP timeout occurs.
   Timeout stays INTENDED/UNKNOWN, never FAILED; a fresh server/component confirms
   by GET without another POST. A real HttpTimeoutException flag is required.

Resolved histories require every retained source's matching actual DATA binding
and local ACK. Business account totals must equal committed operation deltas.
Child halt exit codes and exact markers are checked, rather than treating a random
crash as the configured failure. Original audit source id/time/content retention
is checked across recovery. Existing core1 and source/ACK process4 tests remain.

## Ownership, configuration and evidence

Only a fresh CREATE of an unpredictable gpt_eap_UUID database enables migration
and writes; every child verifies current_database and the owned name pattern.
Flyway precedes proposed V75/V77 and fixture tables. Drop only that successfully
created database, without FORCE or session termination. Child process handles
and unique target/process-logs/gpt-http-* directories are owned by this suite;
stop only those handles, retain their files. Credentials stay in disposable CI
fixture environment, not process arguments/log payload. HTTP server/client logs
use distinct names, preserving the existing 14 worker.log recovery observations.

Both real Keycloak lanes select EffectHttpRecoveryIT. New --effect-http-recovery
strict evidence requires all seven exact class/method identities, current lane,
consistent non-skipped XML, plus the original core and four source/ACK process
cases and real provider cases. Preflight means source presence only. Six new
negative/positive evidence controls cover missing cases, skips/errors, wrong
identity/lane, ambiguity, malformed/oversized XML and CLI dependency preservation.

Local author verification: all 90 application test sources compile (Maven
test-compile -DskipTests), all 88 Python tool controls pass, both strict source
preflights and authored diff checks pass. Actual standalone HTTP/real Narayana
bootstrap --self-check succeeds. Initial compile caught an invalid try-with-
resources assumption about SimpleMeterRegistry; corrected explicit finally
cleanup preserves assertions. No local PostgreSQL/provider execution is claimed.
Fresh exact Linux execution/inspection is complete at the source SHA below.
The initial publication correctly kept that proof pending.

## Executed exact-source evidence

reviewer=GPT (AUTHOR verification only, not peer approval); reviewed/tested SHA
**c67f7afb5201256427cc44abbc725d8866ef2e0e**. Verdict: preparation PASS;
integration HOLD for Claude exact peer/final PR/actual dependency adoption.
This later documentation/tracking commit is not mislabeled as the tested code.

[BOTH39449731514](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39449731514)
is terminal SUCCESS at exactly that SHA, first attempt, no rerun. Both completed
raw logs were downloaded and inspected: standard job118721036782 and native
delegation job118721037134. Actual reactor totals EACH: contracts212/runtime351/
testkit196/application unit391; boundary31/source-delivery17/model24/allocation15
and all other selected component cases pass. Failsafe totals standard15/native20:
HTTP7/source-ACK process4/original core process1 EACH, real standard3/native8.
All four strict evidence markers pass, zero failures/errors/skips.

Actual result archives, downloaded by exact id, with independently verified raw
ZIP bytes against GitHub SHA256, CRC and safe owned-path checks:

| Lane / artifact | Bytes | SHA256 |
| --- | ---: | --- |
| standard12277961758 | 2260690 | 1f93e6d6ce0127386749117ce24cc7fa45c69ff1864e4877eea625bd162e2778 |
| native12279385860 | 2260880 | eb340d1ff133faa9e7b1f408eead0adbaa740ec61f2bcc9d24b9e6df77e74153 |

All159 actual suite XML per lane parsed; positive consistent counters, unique
case identities and no skipped/failed/errored cases. Exact HTTP7/boundary31/
model24/allocation15 method sets match current source. Reran
`python tools/require_keycloak_evidence.py --root <downloaded-lane> --profile
<standard|delegation> --effect-recovery --effect-audit-recovery
--effect-http-recovery` on EACH actual artifact, not preflight fixtures.

Per lane, inspected11 HTTP server logs (NORMAL7/WAIT_CHARGE2/HALT_CHARGE1/
HALT_REFUND1) and two client logs. Exact target charge/refund after-COMMIT halt
markers, client after-reply halt and fresh client SAME_KEY confirmation occur
once EACH. Existing14 worker.log files also retain all four expected source/ACK
halts once plus six intent-create/reopen/four ACK-readback observations. Standard
has one owned WAIT_CHARGE handler InterruptedException on server cleanup; its
committed/release files both remain, actual timeout/recovery assertions pass.
Standalone fixture LogManager bootstrap warnings are not hidden or counted as
application/test failures. Native has no handler-failure marker. No assertion
was weakened and no failed test was retried unchanged.

Actual conformance report/48 outputs/74 exports/two known writer diagnostics also
checked per lane; only the two already bounded wrong fork-order fingerprints
are projected for comparison. Results remain30 MATCH/18 DIVERGED, not full
semantic conformance. No earlier suite/peer approval is transferred.

Source publication and explicit dependency handoff:
[comment6294682324](https://github.com/bassemZohdy/open-workflow-agentic/pull/54#issuecomment-6294682324).
Final local generated/authored-diff checks pass; package/acceptance counters and
all production adoption gates below remain unchanged.

## Explicit handoff and remaining gates

Claude: implement the atomic central appendOnce consumer and actual private
signed-caller/PDP/task/target/lease/occurrence producer through your owned paths,
using the shared contracts and retained original source identities. Do not adopt
the DATA fixture or authority spy. Review frozen #55 before a later GPT PR can
publish this ordered preparation (V71/V72 through the later proposed migrations).
Only GPT integrates after exact peer review/material findings/required CI.

Production bounded I/O/approved adapter non-bypass, full Flow/process restoration,
old-owner/partition recovery, manual resolution/retention/restore/observability and
T03/full WP-108 closure remain open. No acceptance annotation/status promotion,
Flyway placement, CDI wiring, provider permissions, second GPT PR or merge.
Tracking remains 11 completed / 13 partial / 29 not started; acceptance 3/39.


## Subsequent readiness race and correction

Intermediate manual preparation c8197e7/BOTH39454950406 exposed a real existing
HTTP fixture startup race in standard: the parent saw port existence while the
newly created file was still empty (NumberFormatException). Both full logs and
all161 actual XML per lane checked: manual25 passes each; only that HTTP case
errors in standard, native succeeds. Those sources are not a new passing BOTH.
The worker now closes the complete pending port and publishes with ATOMIC_MOVE
in its SAME unique owned directory, after flushing ready output. Parent existence
cannot observe a partially written published file. No sleeps/retry/guard removal
substitute for the publication fix. Local native filesystem/concurrent-reader
probe100 succeeds. Final exacte1c2d8417599c96cbaf336a8f74d945227463aee/
BOTH39457012587 now passes HTTP7/core1/source-ACK4 EACH plus manual26 and real
standard3/native8; both full logs/all161 XML/13 HTTP+14 original process logs per
lane/actual archive bytes-digests inspected. See the current manual-decision
handoff for exact artifacts/remaining gates; no earlier proof or peer transfer.
