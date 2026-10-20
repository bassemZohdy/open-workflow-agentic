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
diagnostic. Large integers/decimals retain precision; boolean-like words such as
on/off/yes/no remain strings. Full YAML scalar/dialect conformance is a separate
gate; this adapter uses the pinned parser's data representation.

YAML aliases and explicit type tags are currently unsupported. In particular,
the pinned Jackson parser reads an alias as its anchor *name*, without resolving
the referenced value. The codec detects that token and refuses conversion;
it does not export a silently changed definition. Original alias source is
retained byte-for-byte. Resolution of these constructs remains open.

Nested metadata, including runtime-looking type/value keys, is ordinary data.
Private parsers load no service-loader modules and bind no Java runtime model.
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

The API is callable by the future authoring service. Durable artifact storage,
access control, approved registry/reference checks, publication and activation
remain with their owners and are not implemented by this adapter. The original
source and declared profile must travel with any candidate artifact; a converted
view is not a replacement for the original. The 42-source corpus is exercised
through this production API, retaining original/converted fixture bytes and the
actual result report in CI. WP-605/H01/H02 remain partial.
