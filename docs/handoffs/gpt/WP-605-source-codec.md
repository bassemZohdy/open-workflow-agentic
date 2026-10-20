# WP-605 source codec handoff

Reviewer=GPT (author verification, not Claude peer approval). Integration HOLD;
exact tested SHA and final CI evidence will be appended after verification.
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

Fresh Linux and both real Keycloak lanes, actual dependency policy, source-
regenerated tracking and final exact-product evidence remain pending. This is
preparation on the existing GPT branch, not a second implementation PR or merge.
