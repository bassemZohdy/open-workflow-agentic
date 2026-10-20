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
| #35 / #37 plan | R1-R3 ownership and interface corrections continue to apply; amended plan needs Claude pass | Do not infer approval from the original request-changes |
| #38 WP-108 | 99f475da7d1f57fc9226a1c40066accdc3629f9c, earlier Claude request-changes was on 222897e | New exact-head Claude pass and CI; partial integration gates stay open |
| #39 Claude WP-102 | reviewer=GPT, 28f6e9bcced23fd865a1bd559508175eab8b6858, scoped PASS | Audit increment CI 39262137012 green; Keycloak 10/10 skipped, not provider proof |
| #40 integration | a4ac085bae94289d15a27b3a900152a2b823e5a2 incorporates reviewed #39 | Claude strict H1 tests/product fixes, exact-head pass and complete CI; WP-102 partial |
| #41 WP-107 | 63dcab3 storage increment, stacked on #38 | CI and Claude review, HTTP/wait-adapter/restart/T13 gates remain open |

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
