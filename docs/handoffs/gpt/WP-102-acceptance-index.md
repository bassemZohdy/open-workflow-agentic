# WP-102 acceptance index correction for Claude a1b1c0b

Reviewer=GPT; source SHA a1b1c0b215d2f00cdd034be123058404de57f450.

Full run39365958905 completed Maven verify successfully, then failed only
AcceptanceStatus --check. Its actual generator reported12 annotated methods,
12 passing and0 missing report entries. The sole proposed change was T07.where,
adding DelegatedAuthorityAcceptanceTest#revokingTheIssuingChainStopsTheTokenWhileAnEquivalentChainStaysActive.
The real Keycloak matrix39365958968 succeeded separately. This tracking failure
is different from the prior fc0376c lifecycle failures; do not conflate them.

## Correction and validation

Commands: gh run view39365958905 --log-failed; gh run view --json jobs/steps
confirmed the sole failed step; parse the EXACT `change: T07: where updated (...)`
emitted by the CI generator against executed reports. Apply that generated value
to docs/requirements/test-status.json, retaining all prior methods and every
status. Render the source as the generator's sorted two-space JSON, then run
python tools/render_requirements.py and --check. All55 local tools tests and
git diff --check pass. No product/test implementation or automated-test count
changes. The source JSON and its rendered test-case projection are the fix.

Verdict=TRACKING-FIX-READY for cherry-pick onto the current Claude carrier. The
original full workflow remains failed; the corrected head must run its required
checks, including AcceptanceStatus --check. The observed CI generator output is
the evidence for this exact index update, rather than a locally unexecuted test.

T07 remains the existing automated in-process acceptance case. This index entry
does not promote complete M11/WP-102, provider acceptance, or any other package.
GPT's a1b1c0b source pass is recorded in #39 comment6282871542; the resulting
published exact head still needs current review/required green CI before merge.

## Resolved main-plan carry

After #35 merged, GitHub reported #39 CONFLICTING with main. GPT therefore
normally merged main c01cef4 into this same carrier: four source JSON array
conflicts combine BOTH progress/evidence and remaining gates; execution_plan
ownership/dependencies match main. CHANGELOG retains both histories. Generated
TODO/plan projections were regenerated;68 tools cases and generated/diff checks
pass. `git diff a1b1c0b -- modules apps .github pom.xml` is empty: product/tests
and workflows remain exactly the source-reviewed/tested a1b1c0b code.

Claude: prefer a normal merge of published gpt/WP-102-acceptance-index, which
contains the minimal744a534 correction AND the resolved main-plan merge. This
also works if the minimal correction was already cherry-picked. Publish the
resulting exact head so required PR CI can run against merged main. GPT alone
handles PR integration. The unfinished V68 ownership work is on a separate
branch and is not needed for this correction.
