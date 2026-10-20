# WP-605 official CTK coverage preparation

Reviewer=GPT (author verification, not Claude peer approval). Integration HOLD.
WP-605/H01/H02 remain partial. No second GPT implementation PR; #55 remains
frozen at ea56f3319d0e896d54703d9f4d857252906ed2a4 awaiting Claude review.

## Scope and actual findings

All 21 scenarios in the 11 official development CTK feature files at upstream
2dd2c84170d5f3e05d58e913e9ca298dcf8d543a are accounted for. Original bytes
were read from the official GitHub Git tree/blobs and length/Git-blob-SHA-1
verified before copying. The committed manifest additionally pins SHA-256 and
byte length of every feature and upstream README. Original license is retained
at conformance/schema/LICENSE; no version or dependency upgrade.

Eleven unchanged definitions execute using pinned SDK 7.35.3.Final / Flow1.2.0.
All24 official assertions evaluate actual output, lifecycle task-start order and
structured WorkflowException fields. Nine scenarios MATCH; two DIVERGE:

- Raise inline error: status/type/title match, but actual instance is
  `do/0/raiseError`; CTK expects `/do/0/raiseError`.
- Implicit-default switch: actual output is `colors: [red, green, blue]`, and
  task starts include switchColor/setRed/setGreen/setBlue. CTK expects unchanged
  `color: yellow`, with switchColor both first and last.

These observed CTK differences require adjudication/fix. Expectations and arrays
are unchanged. Actual observations live under target/conformance/official-ctk;
the coverage baseline records each official assertion outcome. Competing-fork
winner color may vary, but every run must actually satisfy the official one-item
assertion. No observation is sorted, fabricated or converted into MATCH.

Ten scenarios are UNMEASURED: five call, two HTTP-backed data-flow, two HTTP-backed
try and one emit. The existing pure guard rejects them before constructing any
executable definition. No public Petstore/httpbin request or event publication.
Unknown steps/docstrings fail instead of being ignored; negative controls verify
changed order/output/error and missing/non-array properties are detected.

## Verification

Local command: Java25 compiling release21, Maven
`-B -pl modules/oa-runtime -am test -Dtest=OwsConformanceTest,OfficialCtkTest
-Dsurefire.failIfNoSpecifiedTests=false -Dows.updateReport=true`.
Initial report-generation run and final no-update run: ten tests each, zero failures/errors/skips. Tools94 and source-regenerated tracking/check pass. Existing 42-source /
48-execution assessment stays unchanged. Fresh Linux/strict CI evidence is pending and will be recorded before task completion.
The first tracking/tool check ran before the new handoff file was written and failed its missing-evidence validation (two controls). After writing the handoff and regenerating, all94 tool controls pass; no checker was weakened.
Full Windows runtime execution remains limited by the independently reproduced
Cedar JNI failure; focused tests are not described as a full local suite.

This GPT branch normally fast-forwards prior00213ae preparation. Historical
WP-108 DATA process proof remains in its own handoff and does not establish
current production callers or transfer CI approval to this head.

## Claude handoff

Please assess the two newly observed CTK differences with the existing context,
noncompeting-fork and nested-metadata writer findings. Authoring/import/export
with original source retention, controlled external CTK execution, second-runtime
substitution and exact current peer/final PR CI remain open. Review frozen #55
before integration. #53 d38776c / #56 26def97 retain their existing producer,
model, inspector and consolidation gates; no review is inferred from silence.
