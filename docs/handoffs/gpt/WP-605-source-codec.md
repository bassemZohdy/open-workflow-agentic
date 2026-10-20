# WP-605 source codec handoff

Reviewer=GPT (author verification, not Claude peer approval).
Reviewed SHA: **7f6e622b03e04f1f528cba7b3e68af4a5ee03625**.
Verdict: bounded production source API verified; **integration HOLD** for exact
peer and final required implementation-PR gates.
Sole GPT #55 remains frozen ea56f3319d0e896d54703d9f4d857252906ed2a4.

## Producer and consumer

GPT provides `runtime/adapter/source/WorkflowSourceCodec` and its local pinned
schema resources. The actual public API imports bounded UTF-8 JSON/YAML, keeps
exact originals and hashes, exposes immutable bounded diagnostics, and converts
only with explicit formatting-loss acknowledgement and a parsed-data equality
check. No CDI/HTTP route, registry activation, permission or runtime dispatch.
See docs/conformance/source-import-export.md for the API and exact boundaries.

Claude WP-601/control may consume this adapter once reviewed/merged. Persist the
original bytes, original SHA-256, format and chosen profile as the candidate's
source; keep converted views separate. Implement actual artifact storage/access,
registry reference validation and authoring/publication workflows in your owned
paths. Request any durable schema/contract changes from GPT. No control code was
edited here and no new artifact or authorization contract is guessed.

## Scope and failure behavior

All42 fixture originals are retained. The three deliberately invalid sources
and one YAML-alias source refuse conversion. The other38 sources convert to both
formats (76 conversions) with every parsed data value unchanged. Actual fixture
bytes and the result report are retained under target/conformance/source-codec
and target/conformance/source-codec-report.json, including the unknown root field
that schema accepts but the SDK refuses. That is source conversion, not an SDK
compatibility claim.

Private data parsers bind no Java object, load no global/service-loader module,
do not change the global SDK checkpoint serializers and do not resolve source
URIs. The original SDK metadata writer failure and existing conformance findings
remain visible. Source metadata with runtime type/value keys remains opaque.
Aliases/type tags remain explicitly unsupported; the pinned alias parser would
otherwise replace a reference with its anchor name. Full YAML/dialect semantics,
full external CTK, actual durable authoring/publication and second-runtime
substitution remain open. No package or acceptance promotion.

The managed networknt2.0.8 Apache-2.0 validator is promoted explicitly to compile
scope; the BOM otherwise leaves it test-scoped. No version upgrade. Main schema/
license bytes are pinned and compared with independent assessment copies.

## Local verification and historical failures

Focused command: Maven `-B -pl modules/oa-runtime -am test
-Dtest=WorkflowSourceCodecTest,OwsConformanceTest,OfficialCtkTest
-Dsurefire.failIfNoSpecifiedTests=false`, Java25/release21. Final18 codec plus
unchanged10 conformance controls pass, zero failures/errors/skips. The actual
42 originals/76 converted files and report are retained.
The full-reactor `mvn -B verify -DskipTests` dependency/packaging check passed;
it is explicitly not execution proof. Actual dependency lists confirm networknt
2.0.8 compile/Jackson YAML2.22.2 compile, and `check_dependency_policy.py` passes. The first compile exposed the inherited test scope; explicit
compile scope fixes the real dependency. A test initially assumed HttpServer was
AutoCloseable in Java21; it now stops its own server in finally. Three initial
test assumptions were wrong: a private fixture name was schema-valid, and the
pinned parser itself rejects nonfinite spellings/surrogate field names before
our diagnostic layer. Corrected fixtures and exact parser-result codes keep all
rejection assertions intact. No product guard or expected portable output was
weakened. Full local Windows runtime testing retains the independently reproduced
Cedar JNI limitation; compile/package-only checks are not a full test suite.

Tools94, actual dependency policy, source-regenerated tracking/check and authored
diff checks pass. Original upstream schema/license whitespace is preserved, not
trimmed to satisfy a whitespace check. This is preparation on the existing GPT
branch, not a second implementation PR or merge.


## Fresh exact-product Linux and real-provider evidence

All following runs are SUCCESS at the reviewed SHA, attempt1/no rerun. Full logs
were inspected, every actual suite XML parsed for positive/consistent counts,
unique method names and zero failures/errors/skips. Actual ZIP bytes match
GitHub SHA-256/length, pass CRC and bounded safe-path checks.

- [Dedicated39467729394](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39467729394),
  job118783403505: contracts220/runtime372 including source-codec18 and earlier
  conformance10; all81 XML/592 tests inspected.
- [BOTH39467756513](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39467756513),
  standard118783504611/native118783504815: contracts220/runtime372/testkit196/
  app417 EACH, failsafe23/28; all164 XML per lane (1228/1233 tests) inspected.
  Actual standard3/native8/core1/audit-process4/HTTP7/manual-process8 EACH and
  all five strict markers pass. `require_keycloak_evidence.py` was rerun on each
  actual archive with matching profile and all four recovery flags, zero skips.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| dedicated12285977581 | 842742 | 4d9e459ee307d5ab4b66f75684c301ba3d8bdd4cd5bf9b7fdf7d366c5f197473 |
| standard12285472909 | 2624951 | fd2f1b935246b11f5531a1e2f6c19458b9971fdbb47e150062b28851c2bdcab0 |
| native12285544358 | 2624766 | 73232069c5ef03e205adc17febbf0072afb6454b6edb310f33476f802c7b6578 |

ALL three actual archives contain the exact18 production-codec method names,
42 originals matching Git7f6e622 bytes AND reported SHA-256, and76 converted
files independently reparsed/compared against source data (arbitrary precision
JSON/decimal values and boolean-like strings retained). Four refused entries
are exactly unknown-field/missing-task-type/invalid-schema-format (SCHEMA_INVALID)
and comments-and-aliases (YAML_ALIAS_UNSUPPORTED), with no converted files.
The 42-source conversion matrix uses the development schema profile; the release
profile has a separate shape-difference control. Broader released-positive and
full YAML scalar/dialect matrices remain further verification, not inferred.

All three retain the unchanged official21-scenario report/11 actual observations
and earlier42-source model report/48 outputs/74 SDK exports/two writer diagnostics.
The76 source-data conversions are separate from those74 successful SDK model
serializations; the two SDK failures remain visible. Known competing-fork results
satisfy the official cardinality assertion; only the previously admitted two
wrong noncompeting-fork fingerprints are equivalent for regression comparison,
always DIVERGED, with actual order retained. No expectation rewrite.

Both strict archives additionally retain exact process8/storage26/contracts8/
HTTP7/boundary31/model24/allocation15 method sets, original14 audit/13 HTTP logs,
and32 manual worker logs/eight original decisions/56 recorded sessions each.
Halt/outcome/purpose matrices are checked. One owned WAIT_CHARGE cleanup
InterruptedException per lane retains committed/release files and passing
business/timeout assertions. Bootstrap LogManager warnings remain in raw logs.
These inherited bounded DATA fixtures are current regression evidence, not
production authority, signed operator/model or atomic central-consumer proof.

Later evidence updates change handoff/tracking/CHANGELOG only, with code,
resources, schema, contract and acceptance trees unchanged from tested7f6e622.
#55 is still frozen; #53 d38776c/#56 26def97 are unchanged and no current Claude
review exists. Authoring storage/callers/approved references, full YAML/SDK and
external CTK semantics, substitution and exact peer/final PR CI remain open.
WP-605/H01/H02 partial, tracking11 completed/13 partial/29 not started,
acceptance3/39. No activation, merge, second GPT PR or status promotion.
