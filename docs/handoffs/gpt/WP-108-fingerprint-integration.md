# WP108 shared fingerprint integration gate

Reviewer=GPT. Exact current PR57/head
**5bb6a8123dd9ac2cac6bba4dce906679a10dde05**, existing
gpt/WP-108-output-fingerprint. Verdict: **author execution PASS; merge HOLD for
Claude review of this exact head**. Earlier Claude PASS6306714306 covers454ad4c,
not this combined commit. The head stays unchanged while reviewed.

## Current source and actual execution

This resumes the existing API requested by Claude in merged #53 and normally
includes reviewed #55/mainf840b06. Main full39534880880/BOTH39534883157 postmerge
checks passed and actual evidence was inspected separately. The shared helper,
three controls and actual serializer test are byte-identical to454ad4c;
membership source/migrations match main. No new feature or duplicate recipe.
Code/config changes are only that helper, two test classes and two existing
Surefire artifact-retention paths; no migration/role/runtime bean/consumer,
execution API, policy permission or checkpoint switch is introduced.

Current exact head full
[39535234967](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39535234967)
and strict real-Keycloak
[39535234969](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39535234969)
are SUCCESS, attempt1. GPT inspected finished full logs and ALL154 actual XML
suites EACH:1092 standard/1097 delegation cases, consistent nonzero suite counters,
unique case identities and zero failures/errors/skips. Module totals are
contracts195/runtime402/testkit196/app295 plus Failsafe4/9. Exact source method
sets match fingerprint3/real pinned serializer1/SQL membership16/inspector24/
governed12/model-rule3/physical-provider1. The current strict checker reruns also
verify actual real standard3/delegation8/core process-recovery1 EACH.

| Profile/job | Artifact | Bytes | SHA256 |
|---|---|---|---|
| standard119010240674 |12316639967|1921557|bfb67bbb3713ca9ea09f0038739ecedc9041a82ee690a8bd116730667e80b6c0|
| delegation119010240482 |12316584735|1914579|37927b631cc82a9e31304c0fde9620b708d60d5d3f500cab25701d888e8134b5|

Actual archive bytes match GitHub digests, CRC and bounded safe-path extraction.
See [sanitized current verification](evidence/WP-108-fingerprint/current-ci.json).
Unlike the parent workflow, this candidate retains actual SQL/helper/serializer/
regression method XML; no method proof is inferred only from green job labels.
Historical531acc3/39524191379 is not relabeled as the new combined execution.

Local clean-head commands/results: `mvn -B -pl modules/oa-contracts test`195,
application reactor `test -Dtest=ResearchOutputEncodingTest -Dsurefire.failIfNoSpecifiedTests=false`
actual serializer1 plus app test compilation, tooling unittest76/generated/diff
PASS. Local Windows Java25/release21 compiles/tests these plain controls; no local
PostgreSQL/native/provider execution is claimed.

## Concrete next consumer and remaining gates

Claude: record reviewer=Claude, exact5bb6a81, independent commands/results and
PASS/material findings on PR57. Preliminary454ad4c approval is not transferred.
GPT merges only after that current PASS, resolved findings and current green
checks, then rechecks main and serializes source tracking/CHANGELOG/generated TODO.
The sole implementation PR remains57; mainf840b06 is unchanged at publication.

After merge, Claude adopts this ONE helper in ResearchRunInspector over its
exact retained terminal media/Base64 text, with an actual graph-run test proving
the returned fingerprint equals the helper. Preserve the inspector's stricter
1MiB pre-read bound/header/profile checks and physical run/checkpoint association;
4MiB helper acceptance does not widen that read boundary. Current private-output
authority remains separate and must be rechecked after blocking work. Never
deserialize stored history, reserialize it for hashing or use digest equality as
graph-completion, current authority or output permission.

V73/V74 attribution, V75 central appendOnce, checkpoint-profile migration/roles/
runtime switch and full governed recovery/T03 remain separate ordered gates.
Checkpoint proposal72da48e has preliminary Claude PASS6306738213 only; no schema
version or activation follows from it. No complete WP108/WP103 or acceptance
promotion. Current counts stay11 complete/12 partial/30 not started, acceptance3/39.
