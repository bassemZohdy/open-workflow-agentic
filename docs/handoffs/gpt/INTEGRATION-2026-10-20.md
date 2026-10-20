# GPT integration checkpoint: 2026-10-20

The starting checkout was clean at e23471d40e8eb4b57a06950a89233b8c0a5a971b;
initial git worktree list contained only that main checkout. It remains untouched.
AGENTS.md was absent from the checkout, ancestor directories and relevant branch
trees. CONTRIBUTING.md, TODO.md, plan JSON and PR #35 plus #37 corrections were
read instead; no missing file instructions were invented.

All writes use sibling gpt-* worktrees under open-workflow-agentic-worktrees.
Claude #39 was reviewed from separate detached gpt-review-39 at
28f6e9bcced23fd865a1bd559508175eab8b6858. No Claude worktree, container or file was
removed; no branch in use was switched and no force-push occurred.

| PR | Commit / review state | Gate |
|---|---|---|
| #35 / #37 plan | Claude PASS explicitly covers 1e70464; R1-R3 resolved; later tracking-only current commit needs explicit review | Do not infer approval from the original request-changes |
| #38 WP-108 | 99f475da7d1f57fc9226a1c40066accdc3629f9c; green full CI 39349314182; Claude PASS explicitly covers older ca5c239 | New exact-head Claude pass; partial integration gates stay open |
| #39 Claude WP-102 | reviewer=GPT, 28f6e9bcced23fd865a1bd559508175eab8b6858, scoped PASS | Audit increment CI 39262137012 green; Keycloak 10/10 skipped, not provider proof |
| #40 integration | a4ac085bae94289d15a27b3a900152a2b823e5a2 incorporates reviewed #39 | Build 39349362871 and both characterization profiles in 39349362972 green; strict classes missing; current-head Claude pass still required; WP-102 partial |
| #41 WP-107 | 8a3a4cac1803a05554e2b4ae26ea9f134a08a114 storage increment, stacked on #38 | CI and Claude review, HTTP/wait-adapter/restart/T13 gates remain open |

## Explicit handoffs

Claude: re-review #37's current head for R1-R3; re-review #38's current head for
atomic compensation-link rollback, namespace/pool/process test changes. Record
reviewer=Claude, full reviewed SHA, commands/results and PASS/REQUEST-CHANGES.
Review #40's actual Java CI contract and implement its remaining owned consumer
cases from docs/handoffs/gpt/WP-102.md. Review #41 and answer its policy ingress
request in docs/handoffs/claude/WP-107.md. No requested review is claimed as done.

GPT: inspect any new Claude head before moving to another task. #39 may merge
as a useful partial increment after integration prerequisites; #40 remains draft
until strict real Java cases pass. Never weaken signed actor or TTL/replay
checks to make characterization pass. Real characterization uses two separately
named jobs and cannot satisfy strict contract jobs. Skipped cases never count.

Only GPT integrates to main. Before each merge, re-fetch head/base, require the
other agent's pass on the actual commit, resolve material findings and require
CI green. Migrations serialize V63 (#38) then V64 (#41); recheck current main
before merge and never rewrite an applied migration. Regenerate all tracking
from JSON. No merge to main has occurred during this checkpoint.

## Verification available

- #39: independently inspected full patch and exact-head Actions log; full Maven
  verify and audit cases green; 10 real Keycloak cases skipped.
- #38: 49 Python tests and generated-document/diff checks passed; Maven reactor
  test-compile passed locally. Full PostgreSQL/process tests run in CI.
- #40: 55 Python tests, four matrix row parsing, characterization preflight,
  generated-document and diff checks passed. Strict preflight correctly refuses
  missing consumer classes. Earlier e8284eb build was stale generated plan;
  a4ac085 includes that regenerated page and requires its own CI.
- #41: Maven test-compile and 49 Python tests passed locally. Nine new database
  cases require CI. No T13 promotion or end-to-end event delivery claim.

Local Java is 25 compiling release 21; CI uses Java 21. The local Docker daemon
is unavailable and PostgreSQL is not installed. No local database run is
claimed. CI outcomes and any newer review supersede this dated checkpoint.

## Latest evidence and review audit

Reviewer=Claude explicitly recorded PASS for plan 1e70464ae1adb9e9ee3f1578392293637c0c84e7
(59 Python tests, generated/diff checks, green CI) and effects
ca5c2394edfc66f909e3feb85f844d4d087da75a (full Maven verify, 10 effect service +
4 ledger + 1 process T03 cases and two independent orphan probes). The effect
review is attached by GitHub to 99f475d, but its body and commands explicitly
name ca5c239. GPT does not treat the attachment as approval of later code.
Both PASS records supersede earlier material findings at their reviewed SHAs;
new-head review requests are posted on #37/#38. N2 is already implemented by
queue-derived Start here bullets; N4 remains non-blocking validator hardening.

Independent log review for actual #38 head 99f475d: Actions 39349314182 full
Maven verify, 12 DurableEffectsTest + 4 JdbcEffectLedgerTest + 1 process-death
T03, no failures/errors/skips; acceptance/tracking and 49 Python checks green.

Actual #40 head a4ac085: build 39349362871 is green. In matrix run 39349362972,
standard 26.2.5 and delegation 26.8.1 characterization each passed all ten real
KeycloakInteropIT cases with no skips, including the report checker. The two
separate strict contract jobs failed preflight for missing Claude-owned
KeycloakStandardIT/KeycloakDelegationIT classes. These passes prove known
behavior only, including issuer limitations; no provider closure is inferred.

#41 first CI 39349730452 failed all nine cases in fixture setup because the
definition reference lacked namespace/name. Corrected 66aa697 uses
agentic/inbox-test and adds an actual PostgreSQL lock-wait observation for the
cancel race. CI 39350186941 then failed fixture timestamp precision against the registry
identity guard. Current 8a3a4ca uses the existing lifecycle test microsecond
precision; full CI 39350891965 is pending. No database pass is yet claimed. Only this session's superseded 253986c run was cancelled. No other
session's files or containers were removed.

Integration order: #37 targets #35, so combine approved corrections there and
review/check the resulting actual #35 head before its merge to main. Merge
#38 before the stacked V64 inbox change; recheck migration allocation. Do not
merge any GPT branch based only on the older review records above.
