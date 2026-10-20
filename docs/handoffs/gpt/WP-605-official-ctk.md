# WP-605 official CTK coverage preparation

Reviewer=GPT (author verification, not Claude peer approval).
Reviewed SHA: **8b9ae985c79c14e0e9a1f5f7f99f0b66f4ac05bd**.
Verdict: preparation verified; **integration HOLD** for exact peer/final PR gates.
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
Initial report-generation run and final no-update run: ten tests each, zero
failures/errors/skips. Tools94 and source-regenerated tracking/check pass. Existing
42-source/48-execution assessment stays unchanged. The first tracking/tool check
ran before this handoff existed and failed missing-evidence validation (two
controls). Writing the handoff and regenerating fixes that; no checker weakened.
Full Windows runtime execution remains limited by the independently reproduced
Cedar JNI failure; focused tests are not described as a full local suite.

This GPT branch normally fast-forwards prior00213ae preparation. Historical
WP-108 DATA process proof remains in its own handoff and does not establish
current production callers or transfer CI approval to this head.

## Fresh exact-product Linux and real-provider evidence

All runs below are SUCCESS at the reviewed SHA, attempt1/no rerun. Their full
logs were downloaded and inspected, then every actual XML suite was parsed for
positive/consistent testcase counts, unique method names and zero failures,
errors or skips. Every archive was checked against GitHub SHA-256, byte length,
ZIP CRC and bounded safe paths before extraction.

- [Dedicated39463945788](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39463945788),
  job118770289920: contracts220/runtime354, including conformance10. All80 suite
  XML (574 tests), exact official report and all11 actual observations inspected.
- [BOTH39463957692](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39463957692),
  standard118770339928/native118770340445: contracts220/runtime354/testkit196/
  app417 EACH; failsafe23/28, actual standard3/native8/core1 EACH/audit4/HTTP7/
  manual process8. All163 suite XML per lane (1210/1215 tests) inspected.
  `require_keycloak_evidence.py --root <actual archive> --profile <matching lane>
  --effect-recovery --effect-audit-recovery --effect-http-recovery
  --effect-manual-recovery` passes on both actual archives; all five strict
  pre/post markers are present in their CI logs. No skipped provider proof.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| dedicated12284580823 | 777083 | 3fdf0686ca81e0ac228d76c1f08bb35169f8f44293914297612a3ddd4d611e39 |
| standard12285245753 | 2561494 | 0aa52702b86eac53a696cdf154b62e6e38fb47a0338e5025756b3cc7842598d9 |
| native12284024457 | 2562198 | e3a0a682eaa901fd5666d64bd8d85c0132887de2fc3133aa82cce0d50dfc74dd |

All three artifacts retain the exact21-scenario report and11 actual observations;
raise/switch differences and competing-fork cardinality were independently checked.
All three also retain the unchanged 42-source report,48 execution outputs,
74 successful exports and two explicit metadata-writer diagnostics. Only the
previously admitted two wrong noncompeting-fork fingerprints are equivalent for
baseline comparison; both remain DIVERGED and actual order is retained.

Both strict archives additionally retain exact process8/storage26/contracts8/
HTTP7/boundary31/model24/allocation15 method-name sets, original14 audit and13
HTTP logs, and32 manual worker logs/eight original decisions/56 recorded backend
sessions per lane. Original halt/outcome/purpose matrices were inspected. This
revalidates inherited bounded DATA fixtures at the current product; it does not
establish a signed operator, production model/authority producer or atomic audit
consumer. Native includes one owned WAIT_CHARGE cleanup InterruptedException;
committed/release files remain retained and business/timeout assertions pass.
Bootstrap LogManager warnings remain in raw logs, not concealed as application
success. No assertion retry, expectation rewrite or production activation.

The later evidence commit changes only this handoff, tracking and CHANGELOG.
No code/resource/schema/contract or acceptance status changes after tested8b9ae98.
Exact current peer/final required implementation-PR checks still apply before
integration. WP-605/H01/H02 remain partial, tracking11 completed/13 partial/29
not started, acceptance3/39.

## Claude handoff

Please assess the two newly observed CTK differences with the existing context,
noncompeting-fork and nested-metadata writer findings. Authoring/import/export
with original source retention, controlled external CTK execution, second-runtime
substitution and exact current peer/final PR CI remain open. Review frozen #55
before integration. #53 d38776c / #56 26def97 retain their existing producer,
model, inspector and consolidation gates; no review is inferred from silence.
