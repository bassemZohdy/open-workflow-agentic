# WP-605 URI template compatibility adapter

Reviewer=GPT (author verification, not peer approval).
Reviewed SHA: pending product commit below.
Verdict: local compatibility proof PASS; integration HOLD for exact peer,
required final PR CI and explicit production activation review.

## Change and registration boundary

Pinned SDK7.35.3.Final JaxrsURITemplateResolver.resolveTemplates calls
UriBuilder.fromPath on the whole template. Actual javap inspection confirms the
query delimiter becomes path data. ComponentUriTemplateResolver uses fromUri
and the same JAX-RS resolveTemplates/build implementation, preserving URI
components. A defensive LinkedHashMap avoids RESTEasy's containsKey(null)
probe throwing for immutable empty maps; missing variables still refuse.
Priority100 precedes the pinned default1000 if explicitly registered.

No main/test service descriptor, CDI bean or runtime setting activates this
adapter. The builder offers no resolver setter and selects ServiceLoader
providers through the thread context classloader, sorted by ServicePriority.
The integration test creates an owned temporary service descriptor and uses an
isolated URLClassLoader while constructing/executing the actual SDK app. It
asserts the selected provider, restores the context loader in finally and
verifies a subsequent native app still chooses the pinned resolver.
No reflection, SDK jar modification, transport query repair or workflow rewrite.
This remains the pinned JAX-RS template subset, not full RFC6570 support or
host/target authority. Scheme/authority templates require ordinary target policy;
this resolver does not authorize any network operation.

## Verification and retained native finding

Four tests cover path delimiter injection, query ampersand/equal escaping,
fragment separation, literal percent escapes/repeated variables, missing and
non-object inputs, unchanged constant URI, plus actual isolated SPI selection
and the unchanged official HTTP query CTK definition/input/assertions.
The actual requested URI is
https://petstore.swagger.io/v2/pet/findByStatus?status=available,
with one actual loopback response, no provider refusals and all official case
assertions passing. Raw output, source hash, actual original/transport URI and
provider observations are retained as
modules/oa-runtime/target/conformance/uri-adapter/observation.json.

Focused command: mvn -B -pl modules/oa-runtime -am test
-Dtest=WorkflowSourceCodecTest,OwsConformanceTest,CtkProvidersTest,OfficialCtkTest,UriTemplateAdapterTest
-Dsurefire.failIfNoSpecifiedTests=false. Java25 compiling release21:
45 tests, zero failures/errors/skips. No updateReport mode.
Initial four-test run exposed the immutable-empty-map RESTEasy probe and failed;
implementation was corrected without weakening refusal expectations. Final full
focused run passes. Netty Java25 Unsafe warning remains in the log.

Native21-scenario/45-assertion baseline remains byte unchanged:14 MATCH/seven
DIVERGED, including the refused malformed query. This adapter case is a separate
compatibility proof, not a native SDK fix or a production conformance promotion.
Production selection, exact Claude review, final required PR checks, other
six CTK differences and authoring/approved-materialization gates remain open.
Sole GPT#55 stays frozen awaiting Claude; no second implementation PR.

## Exact CI

Pending fresh dedicated Linux and BOTH real-Keycloak runs on this product.
Earlier green CI is historical evidence, not transferred to this change.
