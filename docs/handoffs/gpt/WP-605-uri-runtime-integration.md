# WP-605 default URI resolver runtime integration

Reviewer=GPT (author verification, not peer approval).
Reviewed product SHA: pending product commit.
Verdict: local integration/packaging PASS; exact Linux/BOTH boot verification pending.
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

Fresh exact Linux/BOTH results and actual artifact inspection pending; earlier
078d4c5 results are historical and are not transferred to this changed candidate.
#55 remains frozen ea56f33 awaiting Claude; no second implementation PR/merge.
