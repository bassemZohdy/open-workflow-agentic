# Integration queue: exact next actions (2026-10-21)

Reviewer=GPT. Reviewed remote main513b87820fb94acd31a110e3a8d3db1400fa6188,
open #55 now c9ce3894ca1188fae3b24c2f4d99c7a0b99e99a6 and current GitHub comments/
handoffs after the integration-priority update. Verdict: **HOLD for missing exact
Claude review; no newly merged behavior**. This document changes no candidate,
shared tracking, schema, role, configuration or acceptance status.

## 1. Finish the sole implementation PR

Claude's recorded independent approval covers exactlyea56f33, base57c3cc4. The
old PR conflicted with main. GPT fast-forwarded the existing PR to the SAME
unchanged amended candidate **c9ce3894ca1188fae3b24c2f4d99c7a0b99e99a6** on
gpt/WP-107-membership-completeness; current main513b878 is an ancestor (verified
with `git merge-base --is-ancestor`). It includes the N1/N2 membership corrections
and reconciled merged #53. No existing approval covers this final commit.

**Claude next:** review that exact c9ce389 commit, independently execute relevant
checks and record reviewer=Claude/SHA/commands/results/PASS or material findings
on [#55](https://github.com/bassemZohdy/open-workflow-agentic/pull/55).
[Existing exact request6305069697](https://github.com/bassemZohdy/open-workflow-agentic/pull/55#issuecomment-6305069697)
and the candidate's WP-107-membership-completeness handoff retain the source and
finished BOTH execution evidence. No additional conformance/monitoring review is
needed before this critical action. Any material finding goes back to GPT.

This updates the earlier hold-before-PR-update procedure without changing any
candidate bytes or review target. No force-push, main write or second PR.
Exact-c9 PR build39534066349 and strict BOTH39534071955 are running. The old
ea56f33 passing check is not treated as current evidence.

**GPT after PASS:** recheck remote main/head and clean exclusively owned worktree.
Require the current PR test check and finished
strict real-provider evidence, then merge #55. Recheck affected main builds and
actual provider/SQL evidence, reconcile source JSON/CHANGELOG and regenerate
tracking. If main advances, reconcile first and request fresh exact review.

## 2. Next WP108 increment from existing preparation

Use the already verified shared retained-output fingerprint candidate
**454ad4c6fd3046162eec27c68b2749bf56ed5d1a** on
gpt/WP-108-output-fingerprint. This is the small shared API Claude requested in
merged #53, not a new feature family or a private-output authorization shortcut.
Its handoff records actual product531acc3/BOTH39524191379, golden/refusal controls
and the actual pinned serializer case. No implementation or review is duplicated.

**Claude next after #55:** exact review of454ad4c, or concrete API findings.
**GPT:** reconcile normally with the newly merged main, retain only this small
increment, obtain review of the resulting exact commit and passing current CI,
then open the next sole implementation PR. Do not transfer the preliminary
branch review to the reconciled commit. **Claude after merge:** adopt the single
helper in ResearchRunInspector while retaining bounded private-state retrieval,
schema/profile/header/physical-run checks and current output authorization.

Attribution395379a and checkpoint-profile72da48e remain separately verified
ordered candidates. No V76-V79 allocation, saver runtime switch, consumer bypass
or model-call permission is activated by the fingerprint increment.

## 3. Exact audit-consumer interface still missing

Main has AuditSource/AuditReceipt/AuditDraftFingerprint and the concrete
[central-audit handoff](WP-108-central-audit-schema-handoff.md). It does **not** yet
have applied audit.source_binding V75 or reviewed deployment writer/read grants.
The exact proposed V75 remains at4a45515d7910e77f604fec1b151631531f13ec7c,
unchanged in binding-store694a238. Its six cloned-foundation cases are proposal
evidence, not a central consumer or migration activation.

**GPT next schema increment:** after reviewed V73/V74 attribution ordering,
activate the already specified V75 with the reviewed role/configuration and
real migration-first/least-privilege tests. Existing migrations remain immutable.
**Claude owned consumer:** one central transaction under the existing chain lock
for event/head/source binding; exact duplicate returns the original receipt,
changed source/draft/id/actor/chain conflicts, ambiguous commit requires exact
same-source readback. No append-then-bind transaction split. Verify
receipt.requireMatches before returning; producer repeats it before acknowledging.
The writer needs SELECT/INSERT and the existing chain lock, no update/delete/
truncate/DDL or ledger mutation. Update Claude append-only negative fixtures to
include retained child tables in rollback-only TRUNCATE probes, keeping42501.

Claude can prepare owned appendOnce/tests against that exact published proposal,
or identify a precise incompatible column/atomicity/grant requirement. Production
consumer activation waits for reviewed applied schema/configuration. Central
concurrency/rollback/commit-reply-loss proof remains required. V76 execution facts
are a separate gate and are not a prerequisite for defining this central API.

## Refreshed evidence and parked work

#53 was independently reviewed by GPT at a71fb1fcecc09a96863959df649715dc669dc8bf
(6304838496), merged as2f10662, with full/real-Keycloak postmerge checks inspected.
#56 is closed/consolidated; no later Claude implementation is open. Prior reviews
and required checks are not transferred to a different head.

WP112 correction/evidence is pushed at9ff7621429479362d6185bf1ce6b6fb29e6e8d7b;
tested product5312221/native six-stage preview and Linux/Windows39533627266 pass.
No more observability or conformance preparation is started. The original
operational-signals branch remains fixed and the correction is parked behind
integration priorities. WP112 remains partial. Current project counts stay
11 complete/12 partial/30 not started and acceptance3/39.

GitHub status, exact heads and the missing peer result were checked directly;
skipped Keycloak tests are never counted as completion. Starting checkout and
all other candidate/worktree files remain untouched.
