# OWS 1.0.3 assessment

This is an executable portability assessment of the pinned Java SDK, not a full
conformance certificate or a product authoring/import/export API. WP-605, H01 and
H02 remain partial. The checked-in [report](ows-1.0.3-report.json) records findings,
including divergences. A green test means the assessment reproduced those facts;
it does not turn a divergence into a supported feature.

The profile pins Quarkus Flow1.2.0 / Java SDK7.35.3.Final and normative OWS schema
1.0.3 at specification commit2dd2c84170d5f3e05d58e913e9ca298dcf8d543a. The schema
and one official CTK feature are copied under Apache-2.0 with source hashes,
NOTICE and license in the test resources. No engine/platform version is upgraded.

Run from the repository root:

```powershell
mvn -B -pl modules/oa-runtime -am test '-Dtest=OwsConformanceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Generated actual report, JSON/YAML model exports and outputs of the eight curated
pure fixtures are in modules/oa-runtime/target/conformance. The dedicated
ows-conformance workflow retains them. An intentional reassessment uses the same
command with -Dows.updateReport=true, then reviews the changed report and findings.
Ordinary tests and CI never update the baseline. There is no database, provider,
shell/container/script/agent/model execution in this job. Explicit task/resource
guards protect the pure subset even if a fixture changes. Other cases exercise
only the actual SDK reader/writer APIs, not an alternative hand-written model.

## Measured scope

| Evidence | Current result |
|---|---|
| 34 sources: authored corpus, one extracted official CTK case, two shipped definitions | 31 pass pinned schema, three deliberate schema-negative controls fail |
| Actual default/validating SDK imports | 30 accepted; the three negative controls and a schema-valid unknown-root-field case rejected |
| JSON and YAML export/reimport of the 30 imported models | 60 exports; no authored data field removed or changed in these fixtures; SDK fields are added in21 cases |
| Eight pure fixtures, each as source/JSON export/YAML export | 24 actual executions:15 match, nine differ from the declared expectation |
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

1. **Export/context:** the minimal export-context case sets value42 and exports
   observed from that task output. It returns observed:null instead of42 in all
   three forms. The original dataflow case also returns answer42/observed:null.
   The normative [data-flow rules](https://github.com/open-workflow-specification/specification/blob/2dd2c84170d5f3e05d58e913e9ca298dcf8d543a/dsl.md#data-flow)
   require export.as to use transformed task output and replace workflow context.
   This is an execution finding, not an import/export field-loss finding.
2. **Noncompeting fork:** branches setting side:left and side:right return
   [{left:{side:left}},{right:{side:right}}], while the fixture expects the branch
   outputs [{side:left},{side:right}]. The normative [fork description](https://github.com/open-workflow-specification/specification/blob/2dd2c84170d5f3e05d58e913e9ca298dcf8d543a/dsl-reference.md#fork)
   describes an ordered array of branch outputs. Keep this interpretation visible
   and resolve it before promising that output contract across runtimes.
3. **Schema/model boundary:** an unknown root field is accepted by the pinned
   schema but rejected by the SDK model. An arbitrary string schema format passes
   both import stages; runtime support for that format is not established. JSON
   Schema acceptance alone is not full DSL or executable-provider conformance.
4. **Authoring loss:** model exports are data artifacts. The shipped YAML comments
   and the comment/alias fixture are not reproduced as source text. Preserve
   original authored source separately in a future authoring/import/export service.

The initial loop expectation was corrected using the pinned official
[For CTK](https://github.com/open-workflow-specification/specification/blob/2dd2c84170d5f3e05d58e913e9ca298dcf8d543a/ctk/features/for.feature):
it expects final accumulated state rather than an array of iteration outputs.
Both the authored loop and the unchanged extracted CTK expectation pass. The
assessment does not retain that initial incorrect assumption as a runtime defect.

## Extension and dependency inventory

The machine inventory traverses workflow/task structures, not arbitrary set/body/
schema payload keys. Metadata is a standard extension hook; its application keys
are not automatically a syntax violation. An HTTP or A2A task remains dependent
on its service contract even when its definition is standard OWS.

| Surface | Portability boundary |
|---|---|
| Shipped research-and-approve and lifecycle-probe YAML | Standard HTTP/listen/set constructs; both still declare DSL1.0.0, not1.0.3. Their source is unchanged. |
| Embedded localhost research URL and input effectUrl | Deployment/service dependencies; credentials, registered targets, identity and policy are separate runtime contracts. No HTTP call is made by this assessment. |
| Approval and lifecycle CloudEvent types | Required external event contracts, correlation/ingress/producer configuration; syntax validation does not prove durable delivery. |
| document/task metadata and use.extensions in corpus | Standard opaque metadata/extension hooks. Actual extension behavior is unmeasured. |
| FlowWorkflowEngine and CheckpointingWriter adapters | Quarkus/Java SDK execution and opaque persistence-state coupling. Model roundtrips do not export in-flight execution state. |
| InboxCheckpointMarker oa.inbox.checkpoint.* metadata | Platform-specific runtime receipt/checkpoint protocol; not authored source metadata or permission. |
| ResearchAgent / LlmClient and workflow-references.json | Agent implementation, model/service and registry dependencies; artifact and framework substitution remains unproven. |

Remaining work includes the complete official CTK (including provider/event/
fault semantics), confirmed finding resolution, actual authoring/import/export
API/source retention and a second-runtime/provider/artifact substitution run.
No package or acceptance status is promoted by this bounded measurement.
