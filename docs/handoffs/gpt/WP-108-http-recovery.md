# WP-108 actual HTTP and process recovery preparation

Owner/author verification: GPT. Consumer/reviewer: Claude. Status: preparation;
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
Fresh exact Linux BOTH execution and inspection remain pending at publication.

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
