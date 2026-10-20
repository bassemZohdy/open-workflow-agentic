# WP-605 checked schema-format materialization preparation

Reviewer=GPT (author verification, not peer approval).
Reviewed product SHA: pending product commit.
Verdict: local53 focused tests/package compile PASS; fresh exact CI pending.
Integration HOLD for current Claude review and final required PR checks.

## Producer, consumers and explicit boundary

GPT owns WorkflowSourceCodec.runtimeCapabilityDiagnostics and the new
WorkflowSourceMaterializer. The format check is a separate in-memory overlay of
each pinned released1.0.1/development1.0.3 normative schema: ONLY the declaration
at /$defs/schema/properties/format gains enum:[json]. Pinned schema/license bytes
and ordinary import/schemaAccepted behavior are unchanged. Schema references
identify workflow/task input/output/export, nested composites, functions and
extensions; opaque payload schema/format keys remain data. No manual key search,
model serialization, network access, source mutation or SDK binding occurs here.

Supported declaration capability is omitted/default or exact json. Case changes,
whitespace, unknown formats and explicit version suffixes refuse. The pinned SDK
does not bind format version declarations to a validator version; do not infer
that json:2020-12 is implemented from its default JSON behavior. This is a
capability refusal, not a modification of the normative source schema.

The production adapter has two stages:

1. prepare(imported, conversionPolicy) checks the WHOLE source, then creates an
   immutable JSON executable view with original/executable SHA256 and profile.
   YAML alias values are materialized correctly with explicit presentation-loss
   acknowledgement. Returned bytes are defensive copies. This stage is offline.
2. The caller must approve/save the exact source, executable view and resources.
   materialize(prepared, application) only then binds the actual SDK and creates
   its definition. Supported external JSON schemas may be fetched here. It
   creates no instance and starts no execution; other authority gates remain.

Prepared has a private constructor and is checked data, NOT an approval or
identity/target/execution capability. Original bytes remain in Imported. Missing
shape/capability/conversion gates refuse before SDK construction. SDK binding
errors become bounded SDK_BINDING_UNSUPPORTED diagnostics without data-echoing
messages/causes. Resource failures after approved materialization remain actual
SDK errors; nothing fabricates a successful result.

Claude WP601 publication/authoring must consume this port BEFORE SDK definition
construction and must bind approval to the retained view/fingerprints/resources.
Do not depend on unmerged preparation or replace source approval with schema
acceptance. Existing raw SDK APIs still bypass this adapter and remain explicit
diagnostic paths; they are not an approved authoring ingress. Shipped-definition,
publication/lifecycle adoption, reference/access policy and native-image checks
remain consumer/integration work. The format-check producer can be verified
without claiming those features or whole WP605 completion.

## Exact owned paths and tests

- modules/oa-runtime/.../adapter/source/WorkflowSourceCodec.java
- modules/oa-runtime/.../adapter/source/WorkflowSourceMaterializer.java
- modules/oa-runtime/.../adapter/source/WorkflowSourceMaterializerTest.java
- apps/app-runtime/.../lifecycle/SourceMaterializationRuntimeTest.java
- Existing InputSchemaRuntimeTest fixture is package-visible for the new boot
  test only; its six raw SDK behavior cases and expectations remain unchanged.

Six materializer unit cases cover28 refused sources:14 declaration locations
across BOTH profiles (root input/output, task input/output/export, do/for/fork/
try/catch/listen composites, reusable function and extension before/after).
Every source is normatively valid; unsupported capability refusal happens with
zero actual SDK resource-loader-factory invocations. Reports retain exact
locations/profile/source hashes/diagnostics. Controls cover supported/default
formats, unsupported versions/case/whitespace/secret-like format values without
echo, opaque payload execution, malformed source, explicit YAML conversion
policy, normative-valid SDK-incompatible binding, correct alias SDK execution,
and original JSON BOM/byte/hash defensive-copy integrity.

The real injected Flow boot test refuses unsupported root external, a LATE task
declaration behind an otherwise valid root external schema, and an explicit
version before ANY owned schema GET or task call. Positive JSON source stays
network-free through preparation, then materializes with exactly one real schema
GET and executes one real configured-client HTTP call. Reports retain actual
source/executable hashes, request counters, output and engine id. No client
replacement, transport filter or temporary service descriptor. This is not
application admission, target policy or business audit proof.

Artifacts: runtime target/conformance/materializer/{structural-refusals,
alias-materialization}.json and app target/conformance/materializer/{refusals,
accepted}.json, already retained by the existing workflows.

## Local evidence and retained limits

Java25 compiling release21:
mvn -B -pl apps/app-runtime -am package
-Dtest=WorkflowSourceCodecTest,WorkflowSourceMaterializerTest,OwsConformanceTest,CtkProvidersTest,OfficialCtkTest,UriTemplateAdapterTest
-Dsurefire.failIfNoSpecifiedTests=false

53 focused runtime cases pass, zero failures/errors/skips; application test
sources compile and Quarkus JVM packaging/augmentation succeeds. No Windows
Quarkus boot/SQL/Cedar/provider execution claim. Tools94/current dependency policy,
generated/diff checks pass. Initial exploratory unit fixtures contained an empty
set and a normatively invalid document-field fixture; those fixtures were fixed
using nonempty set and the actual normative-valid root-field incompatibility,
without relaxing capability expectations. Final run has no updateReport mode.

Native SDK CTK14/seven, default URI platform15/six, source codec39 accepted/
78 conversions and raw input-schema four MATCH/two DIVERGED baselines remain
unchanged. Raw SDK fallback is not hidden by the guarded adapter. Fresh exact
Linux/BOTH proof is pending for this changed candidate; old evidence is not
transferred. No new implementation PR/merge/deployment or package promotion.
#55 stays frozen ea56f3319d0e896d54703d9f4d857252906ed2a4. #53/#56 are unchanged,
not yet corrected/consolidated; standing findings remain. Resume WP108 via small
reviewed existing-preparation increments once those peer/consumer gates clear.
