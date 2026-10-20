# WP-605 actual workflow input-schema enforcement and format boundary

Reviewer=GPT (author verification, not peer approval).
Reviewed product SHA: **0a0941bb9259155a26b40ee62a6817c08048ad26**.
Verdict: actual workflow raw-input enforcement and configuration cleanup verified in BOTH; unregistered-format interoperability remains DIVERGED.
Integration HOLD for current Claude review/final implementation-PR gates.

## Configuration and actual checks

Removed only the ignored quarkus.flow.validation.input.enabled property from
application.properties. Pinned Flow1.2.0 has no such configuration property.
No validator, client, schema parser, policy or runtime library has been replaced.
The SDK JsonSchemaValidatorFactory is selected by its existing SPI; the boot
test asserts the actual injected application's factory class.

InputSchemaRuntimeTest has six actual Quarkus boot cases: inline and external
schemas, each with explicit json, omitted format and not-a-registered-format.
Each creates one real SDK definition in the injected Flow application and tries
missing expected, integer expected:5 and valid string expected:raw-valid.
Its input.from would repair either invalid input, so refusal proves validation
uses RAW input before filtering. Both bad values must throw validation/status400
at instance creation, leave zero active instances and send no HTTP task request.
The valid value must run a real configured-client HTTP task once and complete
with the filtered expected:filtered output. Owned loopback servers and exact
GET request records/counters are retained; no transport filter/client replacement.
External schemas are fetched once through the actual SDK resource loader before
instance creation; inline declarations fetch no resource.

Reports are retained as six JSON files under
apps/app-runtime/target/conformance/input-schema in the existing BOTH artifact
upload. Four supported/default cases are MATCH; the two unregistered-format
cases explicitly remain DIVERGED, despite their JSON data checks working.
No unsupported-format acceptance is certified or silently turned green.
This covers workflow input in the actual injected engine, not /instances API
admission, caller/target authority, task input/output/export schemas, mandatory
audit or a real business effect.

## Remaining format gate and consumer handoff

The pinned [reference](https://github.com/open-workflow-specification/specification/blob/2dd2c84170d5f3e05d58e913e9ca298dcf8d543a/dsl-reference.md#schema)
names JSON schema support. The immutable pinned schema additionally describes
an omitted-json default and optional version syntax. Shape acceptance alone
therefore cannot prove a declared format/version is implemented.
Actual SDK bytecode confirms inline SchemaInline.getDocument is used without
reading format. Its external SchemaValidatorFactory callback receives only
ExternalResourceHandler; the declaration's format has already been discarded.
The external unregistered-format case consequently even fetches its owned
schema before validation. An inline-only factory override cannot fix this.

Unknown-format refusal remains REQUIRED before approved executable materialization
and any schema resource access. GPT owns that whole-source capability preflight;
Claude's WP601 authoring/publication consumer must use it before constructing an
SDK definition, rather than treating source-codec schemaAccepted as execution
approval. Preserve original source/shape diagnostics and inspect actual schema
slots, including external and nested task declarations; opaque payload schema
keys are not instructions. This task adds committed evidence and removes a
misleading setting; it does not implement that whole-source gate or closeWP605.

## Local verification

Java25 compiling release21, application reactor package -DskipTests succeeds:
all new test sources compile and actual Quarkus JVM augmentation succeeds.
This is NOT boot execution proof on Windows. Tools94/current resolved-dependency
policy/generated/diff checks pass. A separate ignored pure SDK/filter probe shows
both bad inputs refused400 and valid input transformed to expected:filtered;
that is not substituted for the pending actual boot results.
No full local native Cedar/SQL/provider result is claimed.

## Exact BOTH CI and actual artifacts

Product0a0941bb9259155a26b40ee62a6817c08048ad26, attempt1/no rerun:
[BOTH39483815728](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39483815728)
SUCCESS. Standard118838959603/delegation118838959769 full logs inspected:
contracts220/runtime391/testkit196/app424 EACH, plus failsafe23/28 respectively.
All168/168 actual XML suites (1254/1259 tests) are positive, internally
consistent, unique-method and zero failures/errors/skips.

Actual ZIP bytes/SHA256 match GitHub metadata; CRC, bounded size and safe
extraction paths verified:

| Lane | Artifact ID | Bytes | SHA256 |
| --- | --- | --- | --- |
| standard |12292139015|2715553|50d9af0403dfdce13a7d05b447ead150bd19ea95458e4de94c6d24bc73ea348f|
| delegation |12292982540|2714616|40359997db62b28632d43a876307da0cf205348dadf21dda29e7255ddd26fe18|

Independent inspection in EACH archive verifies all six exact test method names
and six raw JSON observations: explicit/default/unregistered, each inline and
external;18 attempts,12 raw-input refusals with zero HTTP effects, six filtered
COMPLETED executions with distinct valid actual engine ULIDs, six actual GET
/effect requests and three actual GET /schema.json loads. Missing-input error
is exactly required property expected not found; wrong type is integer found,
string expected (raw messages retained). External loads happen once at definition
creation, including unregistered format; inline declarations load none. The
removed configuration key/warning is absent from BOTH complete logs.

Four JSON/default reports are MATCH and two unregistered-format reports are
DIVERGED in EACH profile. Green regression tests do not change that finding
or authorize executable publication. No record/expectation is rewritten to
claim unsupported format support.

Both actual native21-scenario/all45-assertion reports remain14 MATCH/seven
DIVERGED; separate default platform21/45 stays15/six. Exact source hashes and
all20 other scenario records equal; actual controlled HTTP9/resource2/event1/
refusal1 versus platform HTTP10/resource2/event1/refusal0 checked, along with
actual Basic auth/pinned document opens/event ids/time/data, four CTK/five
provider/five URI controls. Actual injected Flow URI two-request boot observation
also independently verified in EACH archive, with exact escaped raw targets and
distinct COMPLETED instances.

Codec26 exact methods,42 originals matching THIS product Git bytes/hashes,
78 independently re-parsed typed-data-equal conversions, profiles8 released/34
development, three SCHEMA_INVALID refusals and actual SDK alias wrong/correct
outputs checked in both. Arrays remain ordered; numbers compare exactly without
boolean/string coercion. Earlier bounded48-output/74-export/two-writer-failure
assessment remains separate; only the two pinned wrong fork hashes admitted,
always DIVERGED.

Actual standard3/delegation8/core1/audit4/HTTP7/manual8 process cases pass.
All five strict pre/post markers pass; rerunning the strict checker on actual
archives with matching profile and all four recovery flags passes. Exact
manual/storage/contracts/HTTP/boundary/model/allocation method sets, actual14
audit logs,13 HTTP logs,32 manual worker logs/eight original decisions/56
recorded backend sessions plus halt/outcome/purpose matrices inspected EACH.
One owned WAIT_CHARGE cleanup InterruptedException per lane stays visible;
actual business/recovery assertions pass. DATA fixtures do not prove a real
operator or atomic central audit consumer. Other bootstrap warnings remain.

No fresh standalone CTK workflow was needed for this app configuration/test-only
increment: BOTH runs execute the complete affected library and application
reactor and retain all its artifacts. No previous CI is transferred as current
proof. Final documentation record changes no tested code/resource/config,
schema, dependency or acceptance state after0a0941b. Tracking remains11 completed/
13 partial/29 not started; acceptance3/39.

#55 remains frozen ea56f3319d0e896d54703d9f4d857252906ed2a4 with no Claude review
at the final audit; #53 d38776c/#56 26def97 unchanged. Keep existing producer/
model/inspector/consolidation requests. Whole-source supported-format/version
preflight must precede SDK definition construction/resource access; task input/
output/export and actual admission remain separate gates. No new implementation
PR, merge or deployment; WP605 remains partial.
