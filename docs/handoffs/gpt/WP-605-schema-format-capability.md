# WP-605 checked schema-format materialization preparation

Reviewer=GPT (author verification, not peer approval).
Reviewed current product SHA:1b8b98b3f05a4d81ce62f731df85c0bd1482cf3b.
Earlier uncached-source checkpoint:5f56bf77db1540f3e14478ad5bf8e94efa0d1735.
Verdict: PASS for producer preparation, including identity-cache conflict/races, exact Linux/BOTH execution and independently inspected actual artifacts. Earlier uncached-source proof remains scoped history. Consumer integration, peer review and package completion remain open.
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

Eight materializer unit cases include28 refused sources:14 declaration locations
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

55 focused runtime cases pass, zero failures/errors/skips; application test
sources compile and Quarkus JVM packaging/augmentation succeeds. No Windows
Quarkus boot/SQL/Cedar/provider execution claim. Tools94/current dependency policy,
generated/diff checks pass. Initial exploratory unit fixtures contained an empty
set and a normatively invalid document-field fixture; those fixtures were fixed
using nonempty set and the actual normative-valid root-field incompatibility,
without relaxing capability expectations. Final run has no updateReport mode.

Native SDK CTK14/seven, default URI platform15/six, source codec39 accepted/
78 conversions and raw input-schema four MATCH/two DIVERGED baselines remain
unchanged. Raw SDK fallback is not hidden by the guarded adapter.

## Historical uncached-source checkpoint verification

Product5f56bf77db1540f3e14478ad5bf8e94efa0d1735, attempt1/no rerun:

- Dedicated [39515799250](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39515799250)
  SUCCESS, job118947525656: contracts220/runtime397, all84 actual XML suites,
  617 tests, zero failures/errors/skips.
- BOTH [39515799389](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39515799389)
  SUCCESS: standard118947534158/delegation118947533770. Each has contracts220/
  runtime397/testkit196/app426 plus failsafe23/28 respectively. All170/170
  actual XML suites (1262/1267 tests), internally consistent/unique-method and
  zero failures/errors/skips.

Actual ZIP bytes/SHA256 match GitHub metadata; CRC, bounded size and safe
extraction paths checked:

| Lane | Artifact ID | Bytes | SHA256 |
| --- | --- | --- | --- |
| dedicated |12307079030|906110|15f957769f161707fc6cb557c3d234806341c3dcb92de4a4501827aae4e5fb05|
| standard |12308435237|2742237|882abf37665682be6071981ca3e2584b5426fb7a3a5202ee12fd2f48ffef0e7b|
| delegation |12307988544|2745858|121bf57b60d654ef9252f44a53b57a3ce931b077667462a8e9c814b3b42a1e2f|

All three archives independently inspected for six exact materializer control
method names,28 raw structural-refusal records covering14 locations in BOTH
profiles, zero SDK construction attempts, exact source fingerprint fields/
bounded unsupported-format diagnostics and actual correct alias materialization
output. BOTH app archives additionally inspected for the exact two real boot
methods and three refusals: root external, late task behind an otherwise valid
root external source, and explicit version. Every case has zero schema GETs and
zero HTTP task calls. The accepted case retains original/executable fingerprints,
one actual schema GET, one actual configured-client HTTP call, COMPLETED output
and a valid engine ULID. Preparation's immutable-copy/hash behavior is asserted
in the unit and actual boot positive controls.

No runtime-code/data/expectation change follows the tested product in the final
documentation record. Earlier evidence is historical, not transferred. Actual
native/platform CTK21-scenario/all45-assertion reports remain14/seven versus15/
six, with only the query scenario differing; all source hashes/other20 records
checked. Actual HTTP9/resource2/event1/refusal1 versus platform10/2/1/0, Basic
auth/pinned document/event observations and URI five/provider five/CTK four
controls inspected in each archive. Actual injected Flow URI boot two requests
and raw-input-schema six reports/18 attempts/12 refusals/six executions/three
schema loads EACH checked: four MATCH/two DIVERGED formats remain visible.

Codec26 exact methods,42 original Git bytes/hashes,78 independently re-parsed
typed-data-equal conversions, profiles8 released/34 development, three shape
refusals and actual SDK alias wrong/correct observations verified in all three.
Arrays retain order; numeric checks do not coerce booleans/strings. Bounded
48-output/74-export/two-writer-failure measurement remains separate; only the
two pinned wrong fork hashes are admitted, always DIVERGED.

Both provider archives pass actual standard3/delegation8/core1/audit4/HTTP7/
manual8 process cases and all five strict pre/post checks. Checker rerun against
actual archives with matching profile and all four recovery flags passes. Exact
manual/storage/contracts/HTTP/boundary/model/allocation methods, actual14 audit
logs,13 HTTP logs,32 manual workers/eight original decisions/56 recorded backend
sessions plus halt/outcome/purpose matrices inspected. One owned WAIT_CHARGE
cleanup InterruptedException per profile remains visible; actual assertions
pass. DATA fixtures do not prove real operator or atomic central audit consumer.

## Definition-cache binding discovered during final review

Actual SDK bytecode shows WorkflowApplication.workflowDefinition uses
computeIfAbsent keyed by namespace/name/version, not executable fingerprint.
The current fix refuses an existing identity before construction and checks the
returned definition's original model identity after computeIfAbsent to close a
concurrent winner race. No cached definition is silently substituted, evicted
or replaced. Even an identical view cannot implicitly reuse an existing identity;
publication/lifecycle must own explicit checked-registry lookup/versioning.

New controls preload a raw SDK definition with an unsupported inline format
and different output under the same identity, then require the checked path
to refuse SDK_DEFINITION_CONFLICT and retain the original cache unchanged.
Five actual concurrent two-view rounds each require one winner, one conflict,
and actual SDK output matching that winner's prepared data. Raw outcomes are
retained in target/conformance/materializer/cache-races.json. Existing alias,
BOM, private-byte/hash and before-fetch controls remain unchanged.
55 final focused cases and application compilation/augmentation pass locally;
fresh exact Linux/BOTH evidence is recorded below. No previous green CI is transferred
to the changed binding code. Earlier5f56bf7 proofs above remain factual for
uncached materialization only; they did not cover this identity-cache gap.

## Current cache-binding product verification

Exact product1b8b98b3f05a4d81ce62f731df85c0bd1482cf3b, attempt1/no rerun:

- Dedicated [39517882571](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39517882571)
  SUCCESS: all84 actual XML suites/619 tests, contracts220/runtime399.
- BOTH [39517882762](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39517882762)
  SUCCESS: standard118954313665/delegation118954313908. Each has contracts220/
  runtime399/testkit196/app426 plus failsafe23/28 respectively. All170/170 actual
  XML suites,1264/1269 tests, zero failures/errors/skips and unique exact cases.

| Lane | Artifact ID | Bytes | SHA256 |
| --- | --- | --- | --- |
| dedicated |12309555080|908077|d4f4af8163904c2ba2c084d5fb992496f33c5ded368e04dd46d244f34e2c888c|
| standard |12310111398|2745027|52279ff9af1669c8bdb241179762c85152d2bdc734f6568181c84e617d6fcb82|
| delegation |12310600183|2750081|cf776285bdc1015d4911926e53236f537eef30256b847d27c4bfd4274bb81d5f|

Actual ZIP bytes/digests match GitHub metadata; CRC/bounds/safe extraction paths
checked. All three archives independently checked against eight exact current
materializer methods,28 structural refusal records and correct alias output.
Each has five actual cache-race records, one winner/one conflict per identity
and SDK output matching that winner. Preloaded raw-cache refusal is an exact
non-skipped method. BOTH archives additionally retain both exact injected Flow
methods: three unsupported declarations refuse before schema GET/task effect,
and approved-view preparation yields one schema GET/one HTTP task/COMPLETED
output with exact source/view fingerprints and valid engine ULID. This proves
the checked adapter's behavior, not caller approval or publication authority.

Author verification commands: focused Maven package55 locally; independently
parse every archived TEST-*.xml and compare current materializer/codec method
sets; reparse original42 bytes/hashes and78 typed-data conversions; inspect
native/platform21 scenarios/all45 assertions, provider observations and actual
URI/raw-input-schema/checked-materializer boot records. Actual native14/seven
and platform15/six findings, raw-schema four MATCH/two DIVERGED and bounded
48 outputs/74 exports/two pinned writer failures remain unchanged.

Both fresh provider archives also pass the exact standard3/delegation8/core1/
audit4/HTTP7/manual8 methods and all five strict evidence checks. Current
profile checker reruns with all four recovery flags pass. All14 audit logs,
13 HTTP logs,32 manual workers/eight original decisions/56 backend sessions
and halt/outcome/purpose matrices checked; known owned WAIT_CHARGE cleanup
interruption remains visible. These are retained DATA preparation checks,
not actual signed operator or atomic central audit-consumer completion.

## Review and integration next steps

The current checked capability/materializer producer is verified as preparation.
Claude WP601 must still adopt the checked port after review/integration, approve
and retain exact source/view/resources and bind publication/access controls.
Raw SDK APIs still bypass it. Other semantic/authoring/reference/deployment and
peer gates keep WP605 partial. Counts11 completed/13 partial/29 not started,
acceptance3/39 unchanged.

Claude reviewed exact #55 ea56f3319d0e896d54703d9f4d857252906ed2a4 with APPROVE
6304103326, including independent PostgreSQL probes. N1 exposes missing direct-
SQL coverage at group advancement; N2 asks for bounded wording. GPT acknowledged
6304285358 and is preparing an additive migration/regression/documentation
candidate in a separate GPT sibling while preserving #55 at that head. The
candidate needs exact review/current required CI before integration. #53/#56
remain unchanged and not corrected/consolidated; standing findings remain.
Resume WP108 through those small reviewed prerequisites. No new implementation
PR, merge or deployment has occurred.
