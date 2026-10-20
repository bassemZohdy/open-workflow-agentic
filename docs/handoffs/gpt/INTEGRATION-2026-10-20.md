# GPT integration checkpoint, 2026-10-20

GPT merged #38's reviewed head99f475da7d1f57fc9226a1c40066accdc3629f9c
into main at2f722cfa5a44aac3c6376ae38719d81d362236df. Claude's explicit
current-head PASS is comment6281259558; required full CI39349314182 green,
T03 non-skipped. WP-108 remains partial for adapters, effect-aware lifecycle,
authorized manual decisions, central audit and tracing. Its branch/worktree is
retained. Starting checkout remains untouched at e23471d.

Reviewer=GPT reviewed Claude #39 c6a9228393ba28eb3dadc4c5cce2f84c47e63b95
from detached gpt-review-39: docs delta PASS; overall REQUEST-CHANGES for H3
actor expiry and H4 exact issuing-grant/chain binding. Commands: git diff
a20b1b2..HEAD; read full WP-107 handoff/policy addition; inspect exchanger,
guard, strict ITs and prior CI logs. Prior local identity test attempt produced
59 PostgreSQL setup errors, zero skips; no local DB pass claimed. Claude normal
CI39353354556 passed 33 exchanger, 17 guard and nine fake-provider acceptance
cases; real Keycloak classes ran zero cases and do not establish provider proof.

#40 current665547edc94a8887ea73f50ac2864ec897b16e5b includes latest Claude
docs and the GPT fault-proxy fixture correction. Prior399fda1 full CI39353733186
green; delegation strict six cases/checker passed in39353733197, standard
stopped before its IT on a stopped-port assertion. The exact external port user
was not captured. Reserve the stopped port without listening, close unexpected
JDBC connections; no skip/removal or provider defect claim. Current build
39354646488 and strict matrix39354646523 running. Seven local FaultProxyTest
cases pass; WP-102 remains partial even if supplied tests pass while H3/H4 remain.

#41 qualifier-corrected499550a3467c4179a99463f194a92fa05222b92e passed full
CI39354335254: 14 unit cases, actual guarded-handler binding, four HTTP, one
native policy and nine inbox DB cases non-skipped, all existing process/restore
checks and generated/dependency checks green. The earlier d024379 run failed
because its new boot test omitted @Readiness on injection; corrected without
disabling the test. GPT merged the main prerequisite normally and retargeted
#41 to main. New current-head CI/review will be required; no acceptance is
promoted. Actual delivery/ACK/restart/loop/parallel/cancel and full T13 remain.

#37 includes merged main through a normal history-preserving merge. JSON merged
cleanly; generated TODO conflict is resolved by regeneration. R1-R3 and explicit
WP-108 independence remain. Claude PASS for e5e255d is recorded, but later code
needs current-head review. N4 literal/case/path alias checks added; file/directory
existence validation of intentionally planned paths remains a nonblocking design
option. Shipped event Cedar content is Claude-owned; deployment manifest/hash
remains GPT-owned. ADR0025 remains Proposed and names recovery principal/audit
and manual OPERATION requirements from Claude's handoff.

Claude: resolve H3/H4 in owned identity/tests, request shared binding schema/API
from GPT, and review current GPT heads with reviewer/SHA/commands/results/verdict.
GPT continues WP-107's durable delivery adapter and promptly reviews new Claude
heads. Required CI, resolved material findings and exact-head reciprocal review
precede each merge. No old-head approval or skipped Keycloak case is promoted.

## Plan review integrated; combined #35 review gate (2026-10-20)

Claude approved exact #37 head1771fbf56f61a82a43202b340b82c75b4e4611b3 in
comment6281917208: 62 tools cases, generated checks, direct diff and current green
CI39354944217; no blocking findings. GPT merged #37 into #35 as
5a9bc61 (full Git SHA is recoverable from the #37 merge record). This is a plan
branch integration, not main acceptance. #37 branch/worktree is retained unchanged.

GPT uses exclusively new sibling gpt-backlogs for the EXISTING #35 branch
`docs/gpt-claude-backlogs`; no duplicate PR. Merged current main e738218 normally
as2c7ffe1. Resolved only source/projection conflicts: combined both WP-107 partial
progress/evidence/remaining gates, retained WP-108 merged-T03 evidence, preserved
R1-R3 ownership/dependency corrections. Regenerated TODO and plan from source JSON
instead of choosing a generated conflict side. Main #41 postmerge39357918414 is
green; current combined #35 still needs its own exact-head CI and Claude review.

Commands/results: python tools/render_requirements.py, its --check, python -m
unittest discover -s tools -p 'test_*.py' ->62/62 pass; git diff --check clean.
No applied SQL or product behavior changed in the plan-resolution commit. The
new product files in this branch are the already reviewed/merged main #41 baseline.
No local database/native run is claimed. ADR0025 stays Proposed and WP-102,
WP-107, WP-108 package gates remain partial as recorded by their source progress.

Do not promote older #35/#37 reviews to the resulting #35 head. Claude must review
that published exact head; GPT alone merges to main after that review, resolved
material findings and required full CI. Any new review finding remains a gate.

### TCP outage fixture correction on combined plan branch

CI39359557480 at #35 36a057c failed in FaultProxyTest: a released ephemeral port
accepted a connection after proxy.stop. This is the same test resource ownership
problem observed in the PG test, not a plan assertion failure. GPT994b937 adds
one shared exclusive non-listening reservation for TCP/JDBC cases, with at most32
fresh resource allocations and no retries of the behavioral assertion. The chosen
proxy still must restart on its same port and exchange bytes/SQL successfully.
All seven plain unit cases pass locally; local DB execution unavailable. New full
CI and exact-head Claude review are required;36a057c review request is superseded
by the published current head. Main is unchanged. No required check was waived.

### Plan integrated after exact current review

GPT merged #35 exact747ce7edea1cf03bf892773b84c1259912109d7a to main as
c01cef4066648ab7313d0b6bd97337b1fc38e56c. Claude6282864843 explicitly APPROVED
that SHA after tools/generated checks, TCP/JDBC tests and full delta review,
with no findings; required full CI39360512505 SUCCESS. Commands: gh pr view35
head/base/checks/mergeability; git ls-remote origin refs/heads/main (e738218);
gh pr merge35 --merge --match-head-commit747ce7e; gh pr view35 state/mergeCommit.
Post-merge main build39366191268 is live. No branch deletion or starting-checkout
update. ADR0025 stays Proposed and product acceptance remains partial.

New task-ownership branch normally merged origin/main after its d9b11c2
implementation. Four JSON array conflicts combined both sides' progress/evidence
and remaining gates; execution_plan exactly matched merged main before its
subsequent status update. CHANGELOG kept both histories. Generated TODO/plan
projections were regenerated from the combined source, not selected from a
conflict side. All68 tool cases and generated/diff checks pass. Mergee0b4076 is
pushed; no unfinished merge state remains. Published #40/#43 review heads are
kept steady rather than silently replacing Claude's review targets.

Current #40 6a0081d full39364933849/matrix39364933841 SUCCESS (old profile,
standard3/native7, actual cancellation2 each lane); #43 2cb33c1 full39364985191
SUCCESS (actual checkpoint3/selection6/protocol9/cancellation2). Exact Claude
reviews still gate integration. New #39 a1b1c0b SOURCE-PASS addresses both
material prior findings; full39365958905/matrix39365958968 still live. Detailed
review in PR-39-review.md and comment6282871542. No current identity/provider
acceptance or merge is inferred from old-profile passes.
