# WP-605 actual workflow input-schema enforcement and format boundary

Reviewer=GPT (author verification, not peer approval).
Reviewed product SHA: pending product commit.
Verdict: actual application test sources/package compile; exact BOTH execution pending.
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

Fresh BOTH real-Keycloak verification pending on this product. The previous
f5b2b23/ea56d7a URI and all earlier CI are historical, not transferred to this
configuration/test change. #55 remains frozen ea56f33 awaiting exact Claude
review; #53/#56 unchanged. No new implementation PR, integration or deployment.
