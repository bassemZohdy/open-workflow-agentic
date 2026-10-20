# WP-605 default URI resolver runtime integration

Reviewer=GPT (author verification, not peer approval).
Reviewed product SHA: **ea56d7adefb2d65680487a00ccfd4c47849c7bac**.
Verdict: exact default JVM integration/packaging/Linux/BOTH boot verification PASS.
Integration HOLD for exact Claude review and final implementation-PR gates.

## Actual change

The main runtime jar now packages
META-INF/services/io.serverlessworkflow.impl.resources.URITemplateResolver,
selecting ComponentUriTemplateResolver priority100 ahead of the pinned
JaxrsURITemplateResolver priority1000 through the actual SDK ServiceLoader.
No feature flag, reflection, SDK jar replacement or test descriptor activates
production behavior. This candidate uses component-aware URI templates by
default when installed; it is not merged or deployed. No dependency changes.
The same JAX-RS template subset/escaping remains in use; this is not a full
RFC6570 implementation and does not confer target or caller authority.

NativeSdkUriResolverScope is TEST ONLY. It hides exactly the one platform
service descriptor from a thread context classloader; refuses missing/duplicate
or mixed provider descriptors; leaves all other resources, classes and SDK
execution unchanged; checks the selected upstream resolver and restores the
previous loader in close, including an exact-message exception control.
It never appears in the production jar.

## Separate measurements

- Original SDK report remains byte unchanged in docs/conformance/official-ctk-report.json:
  21 unchanged scenarios/all45 assertions,14 MATCH/seven DIVERGED. Measurement
  artifacts remain under target/conformance/official-ctk. Its malformed query
  is still recorded/refused before transport.
- Default platform report is docs/conformance/platform-ctk-report.json:
  the SAME21 definitions/inputs/45 assertions,15 MATCH/six DIVERGED, under
  target/conformance/platform-ctk. Exactly the query scenario changes, now with
  one actual correct query request/response; all20 other scenario records and
  all source hashes remain identical. A committed assertion enforces this.
- The focused query observation now says PACKAGED_RUNTIME_SERVICE_PROVIDER,
  with no temporary descriptor or special classloader. Five adapter controls
  cover component/value escaping, literal escapes/repeated variables,
  missing/non-object input, actual default service selection and native-control
  restoration. Native/adapter observations are not substituted for each other.

Six platform findings remain: HTTP and OpenAPI response envelopes, emit output,
raise pointer, implicit switch default and404 error type. Other schema/export/
context/fork/alias findings and authoring/materialization gates remain open.
This is not a complete portability certificate or a second-runtime substitution.

## Actual Quarkus boot test and retained artifacts

UriTemplateRuntimeTest injects the real Flow WorkflowApplication and uses its
configured actual HTTP client. An owned IPv4 loopback HTTP server receives two
real workflow calls: normal path/query and delimiter-containing inputs. Exact
raw request targets, final COMPLETED output, real instance ids and port are
retained under apps/app-runtime/target/conformance/uri-runtime/observation.json.
There is no HTTP filter/URI repair/client replacement in this boot test.
This verifies JVM Flow integration, not lifecycle API admission/target policy,
central audit, a real business effect or public TLS. Actual Quarkus/native-image
compilation and discovery remain unmeasured; JVM selection is not native proof.
Keycloak workflow now retains app conformance artifacts; dedicated CTK trigger
also watches the exact main SPI descriptor.

## Local commands/results

Java25 compiling release21, no updateReport mode in final verification:

- mvn -B -pl modules/oa-runtime -am test
  -Dtest=WorkflowSourceCodecTest,OwsConformanceTest,CtkProvidersTest,OfficialCtkTest,UriTemplateAdapterTest
  -Dsurefire.failIfNoSpecifiedTests=false:47 pass, zero failures/errors/skips.
- mvn -B -pl apps/app-runtime -am package -DskipTests: successful compile,
  test compile and Quarkus JVM augmentation. This command does NOT prove boot tests.
- tools94, current resolved-dependency policy, generated tracking and authored
  diff checks pass. Actual jar/service descriptor packaging checked separately.
- Existing package warning: quarkus.flow.validation.input.enabled is unrecognized
  and ignored in pinned Flow1.2.0. This task makes no input-schema-enforcement
  claim; configuration/actual denial verification needs a separate follow-up.
  Netty Java25 Unsafe and existing bootstrap warnings remain in logs.

## Exact fresh CI and actual artifact inspection

Productea56d7adefb2d65680487a00ccfd4c47849c7bac, attempt1/no rerun:

- Dedicated [39481198334](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39481198334)
  SUCCESS, job118829903772: contracts220/runtime391, all83 actual XML suites,
  611 cases, zero failures/errors/skips.
- BOTH [39481196782](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39481196782)
  SUCCESS: standard118829908343/delegation118829907692. Each has
  contracts220/runtime391/testkit196/app418 plus failsafe23/28 respectively.
  All167/167 actual XML suites (1248/1253 cases) are positive, internally
  consistent, unique-method and zero failures/errors/skips.

Actual ZIP bytes/SHA256 match GitHub metadata; CRC, bounded size and safe
extraction paths verified:

| Lane | Artifact ID | Bytes | SHA256 |
| --- | --- | --- | --- |
| dedicated |12290114936|891126|63892e78f9921c6a50efd7ec605becdb8512478ebb5c58648ab0fcdfc8ca5797|
| standard |12292505500|2686772|29d5755647c54fac3c41bdf57d2f39563da0b835c22b72677acd244fc6267948|
| delegation |12290564370|2694005|8b34eac86e235aa69c3d9122bc4c7d7ece209a3a89e98a9ea8e51d3892ed95a0|

All three archives independently inspected for BOTH21-observation CTK reports,
all45 assertions EACH, native14/seven versus platform15/six, exactly one changed
query scenario and identical other20 records/source hashes. Native actual
HTTP9/resource2/event1/refusal1 and default platform HTTP10/resource2/event1/
refusal0 observed with original/loopback URIs, actual Basic auth match, pinned
schema opens and delivered event ids/time/data. Four exact CTK method names,
five provider controls and five URI controls pass. Packaged-provider focused
query observation retains its original definition hash/two assertions/complete
pet1/statusavailable output and one actual correct-query response.

BOTH app artifacts independently inspected for the exact real boot method and
raw uri-runtime observation. The injected default resolver class is the main
component resolver; two distinct valid engine ULIDs complete with true output.
Actual request targets are exactly:

- /pets/1?status=available
- /pets/a%2Fb%3Fc%23d?status=ready%26admin%3Dtrue

Actual owned port is retained. This has no temporary service descriptor,
classloader override, transport filter or substituted HTTP client. Main runtime
jar AND actual fast-jar dependency packaging inspected for the main descriptor
and absence of NativeSdkUriResolverScope; package-DskipTests is still only
compile/augmentation proof, while these CI boot results provide actual execution.

All three archives also independently checked for codec26 exact names,
42 originals matching product Git bytes/hashes,78 re-parsed typed-data-equal
conversions, profiles8 released/34 development, three SCHEMA_INVALID refusals
and actual SDK alias wrong/correct outputs. No array sorting or boolean/string
coercion. Earlier bounded48-output/74-export/two-writer-diagnostic assessment
stays separate; only the two pinned wrong fork hashes are admitted, always DIVERGED.

Both provider archives: actual standard3/delegation8/core process1/audit4/HTTP7/
manual8 pass. All five strict pre/post markers pass; checker rerun on actual
archives with matching profile and all four recovery flags passes. Exact manual/
storage/contracts/HTTP/boundary/model/allocation method sets and actual14 audit
logs,13 HTTP logs,32 manual workers/eight original decisions/56 recorded backend
sessions plus halt/outcome/purpose matrices inspected. One owned WAIT_CHARGE
cleanup InterruptedException per lane stays visible; actual business/recovery
assertions pass. DATA fixtures do not prove a real operator or atomic central
audit consumer. Bootstrap warnings remain visible.

No tested Java/resources/schema/config/dependencies or acceptance changes after
this product in the final documentation record. Tracking remains11 completed/
13 partial/29 not started; acceptance3/39. Earlier078d4c5 evidence remains
historical, not transferred to this candidate.

## Input-schema follow-up observed while CI ran

Own ignored local probes/logs are target/GptInputSchemaProbe.java,
target/GptUnregisteredSchemaProbe.java, target/gpt-input-schema-probe-with-boot.log
and target/gpt-unregistered-schema-probe.log. They use a standalone SDK app with
pure set task, inline object schema requiring a string expected property,
no URI resolution/HTTP/credentials. Commands use Java25 source-file mode with
actual packaged lib/main/* AND lib/boot/* classpath and the JBoss log manager.
An initial incomplete main-only classpath failed before execution; it provides
no enforcement evidence. Corrected classpath observations:

- Factory is actual JsonSchemaValidatorFactory from the pinned SDK.
- Empty input and expected:5 refuse with validation error/status400.
- expected:fixture completes with accepted:true.
- Replacing format:json with format:not-a-registered-format gives the SAME results.

Actual javap confirms SchemaInline.getDocument is used without reading format.
This local probe is NOT a committed test, application admission/boot proof or
package gate closure. It suggests the ignored setting is redundant for the
standalone default validator, and separately shows undeclared-format fallback
to JSON, which must not be treated as supported format interoperability. Next
work should add committed actual boot denial/format capability cases before
removing the ignored setting or materializing an approved executable source.

#55 remains frozen ea56f33 awaiting Claude; no second implementation PR/merge.
