# WP-605 injected Flow validation and URI proof

Prepared 2026-11-19 on merged baseline `d476aa8d379ce3766b2d5c64ff1dea4e62ee7ea1`.
WP-605 remains partial; no acceptance status changes.

`FlowRuntimeValidationTest` injects the actual Quarkus `WorkflowApplication`. It verifies the selected
`ComponentUriTemplateResolver` and `JsonSchemaValidatorFactory`, then executes the actual SDK and
HTTP provider against an owned IPv4 loopback server. It installs no substitute application builder,
HTTP client, URI-repair filter or schema validator.

Three JUnit tests produce [seven observation records](../../conformance/flow-runtime-report.json):

| Measurement | Evidence |
| --- | --- |
| Normal and escaped URI components | Two actual GETs; encoded path delimiters cannot create new path/query components, escaped query values remain values, and a literal escape is preserved |
| Explicit JSON, inline and external | Invalid missing/wrongly typed raw envelopes raise validation errors before HTTP; valid envelopes are unwrapped before the HTTP task |
| Omitted-format JSON defaults, inline and external | The same invalid/valid and transformation-order checks pass |
| Raw SDK unknown format, inline and external | Still interpreted as JSON: invalid input fails JSON validation, valid input executes; the external schema is fetched despite its unregistered format |
| Checked import of each unknown-format source | `UNSUPPORTED_SCHEMA_FORMAT` before SDK definition registration, schema access or HTTP task execution |

Every generated external source has a distinct schema URL. A previous raw binding cannot warm a cache
and hide an accidental fetch in the checked-import refusal. Valid raw input requires a `payload`
envelope, while the HTTP task receives only its `id`/`tag` contents: successful execution also checks
that schema validation precedes the workflow input transformation.

The raw fallback is an observed gap relative to the application's supported-format profile, not a
claim that the declared format is implemented. The pinned SDK's validation error URI remains
`https://serverlessworkflow.io/spec/1.0.0/errors/validation`; this test does not repair or certify the
error-type portability boundary. The existing source materializer refuses unsupported declarations,
but raw SDK construction still bypasses that explicit authoring boundary.

The test-only [workflow fixture](../../../apps/app-runtime/src/test/resources/conformance/flow-runtime-probe.yaml)
is schema valid against the repository's pinned development 1.0.3 schema. Its only external operation
is a bounded read-only loopback GET, deliberately without retry so an incorrect call cannot be hidden.
It needs no event, credential or model. The tests run on Java 21/PostgreSQL 16 with a disposable database
and an isolated Quarkus test profile that overrides no settings or providers. That profile owns the
injected application's definition cache; test definitions cannot leak into another class's application.

```powershell
mvn -B -pl apps/app-runtime -am test '-Dtest=FlowRuntimeValidationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The local run passed three tests with zero failures, errors or skips. CI retains fresh observations at
`apps/app-runtime/target/conformance/flow-runtime/observations.json`, alongside test XML. The checked-in
report is a retained observation snapshot; the tests write only under `target/`. Exact source/log/report
digests and the fixture-validation command are in the
[local verification manifest](evidence/WP-605-flow-runtime-proof/local-verification.json).

This establishes the measured injected JVM paths. It does not prove native-image discovery, public
TLS/provider availability, a production source/target approval path, deployed role permissions, activation,
all schema dialects, arbitrary schema formats or second-runtime portability. The basic shipped-workflow
input-schema refusal in `AgenticAppTest` remains covered separately. Existing CTK measurements and
their divergences are unchanged. Independent project review remains deferred under the delivery agreement.
