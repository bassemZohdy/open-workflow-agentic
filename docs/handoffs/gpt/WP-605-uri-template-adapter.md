# WP-605 URI template compatibility adapter

Historical checkpoint at078d4c5. Its non-activation boundary is superseded in the
current candidate by [default JVM runtime integration](WP-605-uri-runtime-integration.md).
The exact historical verification below remains unchanged.

Reviewer=GPT (author verification, not peer approval).
Reviewed SHA: **078d4c51512c464943c2281ec42ac1c123d50ba1**.
Verdict: local and exact Linux/BOTH compatibility proof PASS; integration HOLD for exact peer,
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

Product078d4c51512c464943c2281ec42ac1c123d50ba1, attempt1/no rerun:

- Dedicated [39478627368](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39478627368)
  SUCCESS, job118821056899: contracts220/runtime389, all83 actual XML suites,
  609 cases, zero failures/errors/skips.
- BOTH [39478634899](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39478634899)
  SUCCESS: standard118821090101/delegation118821090641. Each has
  contracts220/runtime389/testkit196/app417, plus failsafe23/28 respectively.
  All166/166 actual XML suites (1245/1250 cases) are positive, internally
  consistent, unique-method and zero failures/errors/skips.
- Actual standard3/delegation8/core process1/audit process4/HTTP7/manual8 pass
  each applicable lane. All five strict pre/post checks pass; rerunning the
  strict checker against each actual archive with matching profile and all four
  recovery flags passes.

Actual ZIPs downloaded and bytes/SHA256 matched against GitHub metadata; CRC,
size and safe extraction paths checked:

| Lane | Artifact ID | Bytes | SHA256 |
| --- | --- | --- | --- |
| dedicated |12291126354|878680|9f7933006f4599d41cbd2e6dc85534243c888c1c65c4b51b1d27f894fdf7c0d9|
| standard |12291246940|2662881|08cf974566dea33aaf8bf63764252a56df9bd886a597454693c593e60a236f9f|
| delegation |12291137363|2660080|f81b97f8cfe98ce3f02579973e6cb4a44ebc8cf28cfd8c6a7492b90a8b1de768|

All three artifacts independently inspected for four exact URI adapter control
method names and the raw observation: official source hash
df3df8bd46fc05e7471cb064de402c6d879463189ce2e39f2248ffe93bbd8060,
two original assertions, COMPLETED output pet1/statusavailable, exact original
query URI and corresponding actual IPv4 loopback path/query/port, one response,
no refused requests, resource opens or event deliveries in this case.

The separate native21-observation/45-assertion report stays exact14/seven, with
its HTTP9/resource2/event1/refusal1 observations and provider5 controls verified.
Codec26 exact methods,42 originals matching product Git bytes/hashes,
78 independently re-parsed typed-data-equal conversions, profiles8 released/34
development, three SCHEMA_INVALID refusals and SDK alias wrong/correct actual
outputs verified in all three archives. Arrays remain ordered and numeric
comparison is exact, without boolean/string coercion. Earlier bounded assessment
48 outputs/74 exports/two writer diagnostics remains separate and inspected;
only the two previously pinned wrong fork hashes are admitted, always DIVERGED.

Both provider archives additionally inspected for exact manual/storage/contracts/
HTTP/boundary/model/allocation method names and actual14 audit worker logs,
13 HTTP worker logs,32 manual workers/eight original decision groups/56 recorded
backend sessions and halt/outcome/purpose matrices. One owned WAIT_CHARGE cleanup
InterruptedException per lane remains visible; actual business/recovery assertions
pass. Bootstrap warnings remain in logs. These inherited DATA fixtures do not
prove a real operator or atomic central audit consumer.

Tools94, current resolved-dependency policy and generated/diff checks pass.
Documentation-only final recording changes no tested Java/resource/schema,
contract, dependency or acceptance status after078d4c5. Package tracking remains
11 completed/13 partial/29 not started; acceptance3/39.

## Claude handoff and next gate

#55 stays frozen ea56f3319d0e896d54703d9f4d857252906ed2a4, with no Claude review
at the final audit; #53 d38776c/#56 26def97 unchanged. Keep that current-commit
review first and preserve outstanding producer/model/inspector/consolidation
requests. This prep adds no second implementation PR and does not merge anything.
After the slot clears, review the URI adapter and explicitly choose production
SPI/Quarkus registration, native-image discovery, resource/template compatibility
matrix and ordinary target authorization/approved materialization. Actual
Quarkus CDI/native-image activation is NOT established by the isolated SDK test.
Six other CTK differences and the native URI finding remain recorded; do not
replace the native report with the opt-in positive observation or promote WP605.
