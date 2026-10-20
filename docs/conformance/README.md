# OWS portability assessment

This is an executable portability assessment of the pinned Java SDK, not a full
conformance certificate or a product authoring/import/export API. WP-605, H01 and
H02 remain partial. The checked-in [report](ows-portability-report.json) records findings,
including divergences. A green test means the assessment reproduced those facts;
it does not turn a divergence into a supported feature.

The profile pins Quarkus Flow 1.2.0 / Java SDK 7.35.3.Final and normative OWS schema
1.0.3 DEVELOPMENT profile at specification commit 2dd2c84170d5f3e05d58e913e9ca298dcf8d543a.
The official latest-release API observed 2026-10-20 returns [v1.0.1](https://github.com/open-workflow-specification/specification/releases/tag/v1.0.1),
published 2026-07-31. A separate released 1.0.1 schema at commit f43a6e3cfb97a724d4b434b0ecc769379af379e9
is checked for every source and export too. This does not label development 1.0.3
as a published release or infer released-runtime semantic support. Both schemas
and one official CTK feature are copied under Apache-2.0 with source hashes,
NOTICE and license in the test resources. No engine/platform version is upgraded.

Run from the repository root:

```powershell
mvn -B -pl modules/oa-runtime -am test '-Dtest=OwsConformanceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Generated actual report, JSON/YAML model exports and outputs of the sixteen curated
pure fixtures are in modules/oa-runtime/target/conformance. The dedicated
ows-conformance workflow retains them. An intentional reassessment uses the same
command with -Dows.updateReport=true, then reviews the changed report and findings.
Ordinary tests and CI never update the baseline. The assessment performs no database, provider, shell, container, script, agent
or model execution. The workflow also runs the full affected module suites
against its disposable PostgreSQL service; those existing tests do use the database. Explicit task/resource
guards protect the pure subset even if a fixture changes. Other cases exercise
only the actual SDK reader/writer APIs, not an alternative hand-written model.

## Measured scope

| Evidence | Current result |
|---|---|
| 42 sources: authored corpus, one extracted development CTK case, released-declared variants, two shipped definitions | 39 pass development 1.0.3 schema; 36 pass released 1.0.1 schema. Three deliberate negative controls remain. |
| Actual default/validating SDK imports | 38 accepted; the three negative controls and a schema-valid unknown-root-field case rejected |
| JSON and YAML export/reimport of the 38 imported models | 76 attempts, 74 serialized successfully; two nested-metadata writer failures remain explicit. Successful exports do not remove/change authored data fields in this corpus. |
| Eight pure fixtures plus eight released-declared variants, each as source/JSON/YAML | 48 actual executions: 30 match, 18 differ from the same explicitly declared expectations |
| Selected official For CTK case | Matches original upstream input/output in all three forms; hash and extraction independently checked |
| Lexical source preservation | Serialization changes source text; comments/anchors are not a source-preserving editing format |

Model exports are independently schema checked and validating-reader reimported.
Structural comparisons preserve ordered arrays and distinguish additions, removals,
value/type changes and numeric representation. They do not equate added fields
with harmless defaults or infer execution semantics from model preservation.
Reports use paths/change kinds and output fingerprints; arbitrary source values
and exception messages are omitted. Only curated fictional fixture outputs are
retained in generated execution artifacts.

## Open findings

1. **Export/context:** the minimal export-context case sets value 42 and exports
   observed from that task output. It returns observed:null instead of 42 in all
   three forms. The original dataflow case also returns answer42/observed:null.
   The normative [data-flow rules](https://github.com/open-workflow-specification/specification/blob/2dd2c84170d5f3e05d58e913e9ca298dcf8d543a/dsl.md#data-flow)
   require export.as to use transformed task output and replace workflow context.
   This is an execution finding, not an import/export field-loss finding.
2. **Noncompeting fork:** branches setting side:left and side:right return
   [{left:{side:left}},{right:{side:right}}], while the fixture expects the branch
   outputs [{side:left},{side:right}]. The normative [fork description](https://github.com/open-workflow-specification/specification/blob/2dd2c84170d5f3e05d58e913e9ca298dcf8d543a/dsl-reference.md#fork)
   describes an ordered array of branch outputs. Keep this interpretation visible
   and resolve it before promising that output contract across runtimes. Actual
   provider CI also observed the two named branch results in reversed order.
   The assessment comparison permits only the two explicitly fingerprinted
   wrong outputs for these two fork fixtures; both must remain DIVERGED.
   Unexpected outputs still fail, ordered-array comparisons elsewhere stay exact,
   and generated reports/output artifacts retain the actual observed order.
3. **Nested metadata serialization:** JSON and YAML writes of nested task
   metadata both fail with JsonMappingException/JsonGenerationException. The
   actual serializer is Quarkus Flow TaskMetadataSerializer, not an alternative
   handwritten exporter. These are serialization failures, not successful exports.
   The nested metadata fixture remains part of the assessment.
4. **Schema/model boundary:** an unknown root field is accepted by the pinned
   schema but rejected by the SDK model. An arbitrary string schema format passes
   both import stages; runtime support for that format is not established. JSON
   Schema acceptance alone is not full DSL or executable-provider conformance.
5. **Authoring loss:** model exports are data artifacts. The shipped YAML comments
   and the comment/alias fixture are not reproduced as source text. The source codec now preserves
   original bytes; durable authoring storage and activation remain separate gates.

The initial loop expectation was corrected using the pinned official
[For CTK](https://github.com/open-workflow-specification/specification/blob/2dd2c84170d5f3e05d58e913e9ca298dcf8d543a/ctk/features/for.feature):
it expects final accumulated state rather than an array of iteration outputs.
Both the authored loop and the unchanged extracted CTK expectation pass. The
assessment does not retain that initial incorrect assumption as a runtime defect.

## Extension and dependency inventory

The [offline source codec](source-import-export.md) now provides an actual
source-preserving import/export API for candidate authoring, with pinned schema
validation and explicit conversion-loss handling. It is separate from SDK model
serialization and from activation; its remaining authoring/semantic gates are
documented with the API.

The machine inventory traverses workflow/task structures, not arbitrary set/body/
schema payload keys. Metadata is a standard extension hook; its application keys
are not automatically a syntax violation. An HTTP or A2A task remains dependent
on its service contract even when its definition is standard OWS.

| Surface | Portability boundary |
|---|---|
| Shipped research-and-approve and lifecycle-probe YAML | Standard HTTP/listen/set constructs; both still declare DSL 1.0.0, not 1.0.3. Their source is unchanged. |
| Embedded localhost research URL and input effectUrl | Deployment/service dependencies; credentials, registered targets, identity and policy are separate runtime contracts. These shipped definitions are not executed by the round-trip assessment. |
| Approval and lifecycle CloudEvent types | Required external event contracts, correlation/ingress/producer configuration; syntax validation does not prove durable delivery. |
| document/task metadata and use.extensions in corpus | Standard opaque metadata/extension hooks. Actual extension behavior is unmeasured. |
| FlowWorkflowEngine and CheckpointingWriter adapters | Quarkus/Java SDK execution and opaque persistence-state coupling. Model roundtrips do not export in-flight execution state. |
| InboxCheckpointMarker oa.inbox.checkpoint.* metadata | Platform-specific runtime receipt/checkpoint protocol; not authored source metadata or permission. |
| ResearchAgent / LlmClient and workflow-references.json | Agent implementation, model/service and registry dependencies; artifact and framework substitution remains unproven. |

The [official CTK coverage report](official-ctk-report.json) now accounts for all
21 scenarios across the 11 feature files at the same development commit. Original
feature and README bytes, lengths and SHA-256 digests are retained under
`conformance/upstream/ctk/`; its Apache-2.0 license is retained in the adjacent
schema corpus. This is a strict reader for those pinned Gherkin steps, not a
general Cucumber implementation. Unknown steps and malformed docstrings fail.

All21 unchanged definitions now execute with the actual SDK: eleven pure and
ten with controlled providers. All45 official assertions are evaluated. The
current report records14 MATCH/seven DIVERGED, with no unmeasured scenario in
this pinned development kit. This is measurement, not a conformance certificate.

Controlled HTTP uses the actual SDK executors/auth/output, a contextual JAX-RS
client and the existing Quarkus Jackson reader. A per-client filter records the
original URI, then routes only exact fixture GETs to an owned loopback server.
The actual OpenAPI reader loads an unmodified, byte-pinned Apache-2.0 Petstore
schema through an exact-URI loader; HTTP operations still use the same guarded
transport. Emit uses the actual SDK CloudEvent construction and in-memory broker
with recorded subscriber delivery. Source/input/official expectations are
unchanged. This does not prove public provider availability/TLS or production
identity, target permission, audit or delivery durability.

The seven observed differences requiring resolution or adjudication are:

- HTTP URI interpolation encodes the query separator into the path:
  `findByStatus%3Fstatus=available`. The guard records/refuses that malformed
  request before transport; it does not repair it or supply an invented result.
- HTTP and OpenAPI `output: response` return content without the required response
  envelope (two scenarios), despite actual loopback200 responses.
- Emit actually delivers the expected CloudEvent/data, but workflow output stays
  the original user input instead of the envelope expected by the CTK.
- Raise uses `do/0/raiseError` instead of the expected leading-slash pointer.
- Implicit-default switch runs following tasks instead of ending at switchColor.
- The404 catcher requests the new open-workflow error URI, while the actual SDK
  raises the older serverlessworkflow1.0.0 communication type. It remains faulted;
  the503 catcher correctly leaves that404 uncaught in the positive control.

Actual output/error/task starts, original/transport URIs, resource opens, event
id/time/data and refusals remain in CI observation artifacts. The baseline
compares official assertion outcomes and deterministic provider summaries;
random event ids/times and ephemeral loopback ports remain in raw observations.
Neither arrays nor wrong results are sorted or replaced to satisfy expectations.
The earlier42-source/48-execution model assessment is unchanged and remains a
separate bounded measurement.

A first local exploratory run mistakenly registered the HTTP client through the
SDK's global Supplier overload, which populates a different map from the
contextual lookup used by HTTP execution. That run bypassed the intended client
and issued fixture-only public GETs; its results are INVALID as controlled
provider evidence and retained separately in the owned local target directory.
The corrected contextual binding is checked before definition construction, and
a regression refuses the wrong binding before any SDK workflow can execute.
The actual standalone Quarkus Jackson reader is also explicitly client-scoped;
no global runtime configuration, proxy, DNS or trust setting is changed.

Remaining work includes finding adjudication/resolution, broader released and
public-provider/TLS checks, actual durable authoring/approved materialization
and a second-runtime/provider/artifact substitution run. No package or acceptance
status is promoted by this controlled measurement. See the current handoff in
docs/handoffs/gpt/WP-605-controlled-ctk.md for exact verification evidence.

The released-declared execution variants change ONLY document.dsl and
document.version to 1.0.1, retaining the same tasks/input/expected results. A test
checks that derivation. They are comparisons on the pinned stack, not the complete
released CTK or a claim that development-text interpretations are all settled.
Three extra development-valid shapes fail the released schema: the emit URI,
gRPC host and shell argument forms in this corpus. The controlled CTK protocol measurements above are separate from these
model-shape samples; no general public-provider execution claim follows. The report includes the actual host metadata serializer class so
stack-specific behavior is not misattributed to a bare upstream SDK.

The candidate runtime packages `ComponentUriTemplateResolver` as its default
URI template service. It preserves URI components through JAX-RS `fromUri`.
The native SDK measurement above is isolated through a test-only descriptor
filter; its original report is unchanged. The default platform has its own
[CTK report](platform-ctk-report.json) and actual observation artifacts.

| Measurement | Same original scenarios/assertions | MATCH | DIVERGED |
| --- | --- | --- | --- |
| Pinned upstream SDK |21/45|14|7|
| Default platform service |21/45|15|6|

Only the HTTP query case changes; all other scenario records and definition
hashes are equal, enforced by a test. A separate real injected Quarkus Flow
boot test verifies normal and escaped URI inputs on actual owned loopback HTTP.
This candidate has not merged or deployed. JVM packaging/discovery does not
establish native-image discovery, target authority, public TLS or full semantic
portability. See [runtime integration and exact verification](../handoffs/gpt/WP-605-uri-runtime-integration.md).
