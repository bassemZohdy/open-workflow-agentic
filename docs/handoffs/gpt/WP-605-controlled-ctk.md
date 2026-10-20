# WP-605 controlled full pinned CTK measurement

Reviewer=GPT (author verification, not peer approval).
Reviewed SHA: **3c76d38f5455e4efe59c74dea2c2bbb754c9b869**.
Verdict: controlled full pinned CTK measurement verified; **integration HOLD**
for exact peer and final required implementation-PR gates.
Sole GPT #55 is frozen ea56f3319d0e896d54703d9f4d857252906ed2a4 awaiting Claude.

## Producer, boundary and actual results

All21 original development CTK scenarios at2dd2c841 execute unchanged with the
pinned actual SDK. All45 official assertions are evaluated:14 MATCH/seven
DIVERGED, zero unmeasured in this kit. Unknown Gherkin steps still fail. Eleven
pure definitions use the existing pure guard; ten provider-backed definitions
use a separate structural guard refusing commands/listeners/unregistered calls,
remote source schemas and reusable/scheduled resources. Opaque data keys are
not interpreted as commands. This is not a full semantic conformance certificate.

Actual SDK HTTP executors/auth/output use a contextual JAX-RS client, the actual
Quarkus Jackson reader and an exact GET allowlist routed to an owned loopback
HTTP server. Original requested URIs/method/auth-match and actual responses are
recorded. The actual OpenAPI parser reads UNMODIFIED Petstore Swagger2 bytes
13843/SHA256bb116730d391e330119c691f9b124d07b9afdea61b5a252345b577e9a4c2f98b
from its original exact resource URI, then calls the same HTTP transport.
Snapshot source/time/license are retained under conformance/providers with its
Apache-2.0 license/notice; no document operation/security/reference is rewritten.
Emit uses actual SDK CloudEvent construction and an actual in-memory broker;
subscriber delivery id/time/data are retained. No global DNS/proxy/trust/mapper
or production runtime configuration changes. This does not prove public TLS,
target authority, mandatory audit or durable event delivery.

Observed CTK differences, requiring Claude/GPT adjudication before portability
claims, are query-separator encoding into the URI path (guarded/refused), HTTP
and OpenAPI response-envelope output (two scenarios), emitter output retaining
workflow input despite actual correct event delivery, the raise pointer's
missing slash, implicit-default switch flow and the404 catcher's error-type URI
mismatch. Raw behavior and every official expectation remain visible. The503
catcher genuinely observes an uncaught404; non-object/output filtering and Basic
authentication exercise actual loopback responses successfully.

The baseline includes deterministic original request/resource/refusal summaries.
Actual ports, event ids/times and outputs stay in observation artifacts. One
incorrect query is explicitly refused before transport; it is not counted as a
successful service call. Normal provider scenarios yield nine actual HTTP
responses, two real schema opens and one delivered event across the corpus.
The earlier42-source/48-output/74-successful-SDK-export/two-writer-failure
assessment and source-codec42-original/78-conversion/alias probes stay separate.

## Controls, failures and local verification

Five provider controls exercise actual HTTP+JSON transport and unknown-target
refusal, exact resource bytes/unknown-URI refusal, real broker delivery/type
refusal, structural source guarding and wrong-binding refusal before execution.

The first full exploratory run used the Supplier overload of withAdditionalObject,
which populates a global map; HttpClientResolver uses a separate contextual map.
It bypassed the intended client and made public fixture GETs. Its apparent green
JUnit/report is INVALID controlled-provider proof. The original log/report/21
observations remain in target/gpt-ctk-invalid-supplier-binding; no CI proof is
claimed from that run. Contextual BiFunction bindings and an explicit identity
precheck BEFORE definition creation now close that gap. A negative control
refuses the original wrong binding without constructing any SDK workflow.
An initial guard exception-type mismatch was corrected with a constant explicit
IllegalStateException, preserving the refusal assertion. The existing actual
Quarkus Jackson reader is registered per client for standalone JSON mapping;
no workflow output or error is patched.

Final no-update local command: Java25/release21, Maven `-B -pl modules/oa-runtime
-am test -Dtest=WorkflowSourceCodecTest,OwsConformanceTest,CtkProvidersTest,OfficialCtkTest
-Dsurefire.failIfNoSpecifiedTests=false`.41 tests pass, zero failures/errors/skips.
Full local Windows module execution remains limited by independently reproduced
Cedar JNI; focused results are not called a full local suite. Tools94, dependency policy, source-regenerated/check and authored diff checks
pass. Fresh exact Linux/BOTH evidence follows. Netty Java25 deprecation
warnings remain in raw logs; no runtime flag silences them.

## Claude handoff and acceptance gates

Review the seven differences with existing context/fork/metadata/alias findings.
Do not assume schema/model acceptance, actual local transport or a green
regression baseline means full DSL conformance. Preserve original authored
bytes/profile and separately approve executable materializations in WP-601.
Actual artifact/access/reference/publication consumers and production authority/
operator/model/central-audit producers still require their owners. No control
code, shared contract, schema migration or runtime activation is edited here.
Exact peer and final required implementation-PR CI remain gates. WP-605/H01/H02
partial; no second GPT PR, merge, status or acceptance promotion.


## Fresh exact-product Linux and real-provider evidence

All following runs are SUCCESS at the reviewed SHA, attempt1/no rerun. Their
full logs were inspected. Every actual suite XML was checked for positive and
consistent counts, unique testcase names and zero failures/errors/skips. Actual
ZIP bytes match GitHub length/SHA-256 and pass CRC plus bounded safe-path checks.

- [Dedicated39475965130](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39475965130),
  job118811879382: contracts220/runtime385; all82 XML/605 tests inspected.
- [BOTH39475965899](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39475965899),
  standard118811888188/delegation118811888707: contracts220/runtime385/testkit196/
  app417 EACH, failsafe23/28; all165 XML each (1241/1246 tests) inspected.
  Actual standard3/delegation8/core1/audit-process4/HTTP7/manual-process8 EACH
  pass with all five strict pre/post markers. Matching-profile evidence checker
  reruns against both archives, with all four recovery flags, pass without skips.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| dedicated12288573440 | 865736 | f4d1f9626177d8891cab225cfbe9b8877a9bb52dea7fb3bb2ad1b4ccaecf57da |
| standard12290045212 | 2646778 | 5649486157cadfefaa682042f10c2ffe9caec69c3aa20670741a95fd909cad5f |
| delegation12288984610 | 2650260 | 3d76a06061e79db6b813fe2295ee80b857a91a1fb36317a0753c8a2f526951ef |

ALL three archives contain the exact full CTK report and21 raw observations;
all45 official assertions and three CTK control method names were checked.
Five provider-control method names match current source exactly. Original URIs,
actual loopback transport addresses/ports, GET methods, Basic-auth match,
response counts, exact resource loads, pinned schema digest, event id/time/type/
source/data and the one malformed-query refusal were independently inspected.
Each archive shows nine actual HTTP responses/two schema opens/one delivered
CloudEvent/one pre-transport refusal. Deterministic request summaries equal the raw
request records with only the ephemeral transport URI omitted; resource/refusal
summaries and event counts match their raw records. Full ports and event ids/
times remain retained. This is the CORRECT contextual binding, not the invalid local
Supplier run. The seven actual semantic differences remain DIVERGED.

ALL three also retain exact26 source-codec method names,42 originals matching
Git3c76d38 bytes AND reported hashes,78 independently reparsed typed-data-equal
conversions, released8/development34 profile selection, three precise refusals
and actual wrong-original/correct-materialized SDK alias observations. Typed
comparison refuses bool/int/string coercion. The earlier model report/48 actual
outputs/74 SDK exports/two writer diagnostics remain unchanged and separate.
Only its two previously admitted wrong noncompeting-fork fingerprints are
equivalent for regression comparison; they always remain DIVERGED, with actual
array order/output retained. No portable expectation was rewritten.

Both strict archives additionally retain exact process8/storage26/contracts8/
HTTP7/boundary31/model24/allocation15 method sets,14 original audit/13 HTTP logs,
32 manual worker logs/eight original decisions/56 recorded backend observations
each and the required halt/outcome/purpose matrices. One owned WAIT_CHARGE
cleanup InterruptedException EACH has committed/release files retained and
passing business/timeout assertions. Bootstrap warnings remain in raw logs.
These bounded DATA-fixture regressions do not establish production signed
operator/model/authority or an atomic audit consumer.

Later evidence updates touch only handoff/tracking/CHANGELOG; code/resources/
schema/contracts/acceptance stay unchanged from tested3c76d38. #55 still lacks
current Claude review; #53 d38776c/#56 26def97 are unchanged. The seven findings,
public TLS/providers, durable authoring/approved materialization/reference/
publication, substitution and owner/peer/final PR gates remain open. WP-605/H01/
H02 partial; tracking11 completed/13 partial/29 not started, acceptance3/39.
No activation, second GPT PR, merge, certificate or status promotion.
