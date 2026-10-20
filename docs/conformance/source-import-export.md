# Offline source import and export

`WorkflowSourceCodec` is an explicit runtime adapter for authoring candidates.
It accepts a bounded UTF-8 JSON or YAML byte array, retains an immutable copy of
the exact bytes and their SHA-256, and validates source data against a caller-
selected, locally pinned schema profile. Profiles are the released 1.0.1 and
development 1.0.3 baselines already used by the assessment. The codec never
rewrites document.dsl or document.version. A profile checks schema shape;
it does not establish that the declared dialect executes on the current engine.

```java
var codec = new WorkflowSourceCodec();
var candidate = codec.importSource(authoredBytes, WorkflowSourceCodec.Format.YAML,
        WorkflowSourceCodec.Profile.DEVELOPMENT_1_0_3);
var original = codec.exportOriginal(candidate); // exact bytes, including invalid candidates
if (candidate.schemaAccepted()) {
    var json = codec.export(candidate, WorkflowSourceCodec.Format.JSON,
            WorkflowSourceCodec.ConversionPolicy.ALLOW_FORMATTING_CHANGES);
    // Check json.succeeded() before using json.bytes(); retain original separately.
}
```

Every array crossing the API boundary is defensively copied. Diagnostics are
immutable codes with bounded paths/keywords; parser/validator exception messages
and caller values are not returned. Empty/over-1-MiB inputs are refused before
copying. Invalid UTF-8 is never replaced. Duplicate keys, trailing documents,
invalid Unicode, non-JSON values and bounded-depth/token/string/number violations
refuse conversion. The exact original remains exportable for diagnosis/editing.

Conversion requires the explicit formatting-loss policy. PRESERVE_ORIGINAL
requires the original format and returns the original bytes. A conversion never
claims to preserve comments, whitespace or anchor presentation; the result
carries a presentation notice and the original source hash. Conversion reimports
its output under the same schema profile and compares every parsed data value.
Any data change or output-limit violation returns no converted bytes and a
diagnostic. Large integers/decimals retain precision; numbers compare by their
exact mathematical value, not Java numeric-node class or floating-point coercion.
Boolean-like words such as on/off/yes/no remain strings.

YAML source now uses representation-graph composition and an explicit
[YAML 1.2 core tag resolver](https://yaml.org/spec/1.2.2/#1032-tag-resolution).
Shared alias values expand into JSON data; their original anchor names/comments
remain in the exact source. Expansion has separate node/character/depth budgets,
and serialization stops at the output byte limit before returning any partial
bytes. Cycles and non-string mapping keys cannot be represented as JSON and
refuse conversion. Undefined aliases and duplicate resolved keys also refuse.

Standard core string/null/boolean/integer/float/sequence/map tags are supported.
Finite decimal, octal and hexadecimal values use arbitrary-precision arithmetic;
legacy binary/sexagesimal/underscore spellings and timestamps remain strings.
Custom Java tags and non-core YAML types (including binary/set/timestamp/merge
tags) refuse conversion. Nonfinite numbers refuse because JSON has no matching
value. Explicit YAML version directives must be 1.2; quoted directive-looking
text is ordinary data. This is tested core resolution, not a complete YAML
parser certification or arbitrary-tag adapter.

Nested metadata, including runtime-looking type/value keys, is ordinary data.
Private parsing/composition loads no service-loader modules and binds no Java runtime model.
The conversion serializes source data directly, so it preserves metadata without
using the host's typed checkpoint metadata serializer. That serializer and the
known SDK nested-metadata writer failure remain unchanged and separately tested.
Unknown fields accepted by a schema are retained; schema acceptance must never
be substituted for SDK/model acceptance or execution semantics.

Only the two fixed schema resources are loaded. Their exact SHA-256 digests are
checked, all normative `$ref` values must be internal, and source-provided schema,
HTTP/OpenAPI, tool, agent and event URIs are never resolved or executed. The codec
does not construct a WorkflowApplication, register/activate a definition or
publish an event. It grants no caller, policy or artifact approval.

The existing managed Apache-2.0 networknt 2.0.8 dependency moves explicitly from
test to compile scope for this real source-validation path. No version changes.
The schemas and original license now also ship as main resources; tests compare
their bytes with the independently pinned assessment corpus.

SnakeYAML 2.6 is now an explicit dependency at the same already-transitive managed
compile version. Only scanner/parser/composer/node APIs are used; no YAML load(),
object constructor or runtime class resolution. The raw stream is bounded before
graph construction, then expanded JSON data is bounded again.

The current SDK YAML reader still substitutes an alias name for its referenced
value. A fixed pure workflow test records that wrong output and the correct
output from codec-materialized JSON, using the actual SDK both times. The older
model assessment compares the pinned parser's structural data and therefore
does not prove alias semantics. This source codec fixes the authoring view;
runtime compatibility/approved materialization still needs checking before
publication or activation. Other SDK conformance findings remain open.

The API is callable by the future authoring service. Durable artifact storage,
access control, approved registry/reference checks, publication and activation
remain with their owners and are not implemented by this adapter. The original
source and declared profile must travel with any candidate artifact; a converted
view is not a replacement for the original. The 42-source corpus is exercised
through this production API, retaining original/converted fixture bytes and the
actual result report in CI. WP-605/H01/H02 remain partial.
