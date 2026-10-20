# WP-605 YAML core source semantics

Reviewer=GPT (author verification, not peer approval). Integration HOLD; exact
product SHA and fresh Linux/real-provider evidence will be appended when done.
Sole GPT #55 remains frozen ea56f3319d0e896d54703d9f4d857252906ed2a4.

## Producer and actual consumer boundary

The production source codec now expands YAML aliases and resolves the standard
core tags into JSON data through a bounded representation graph. Its scanner,
parser and composer never invoke YAML load(), a Java object constructor or
runtime class binding. SnakeYAML2.6 is explicit at the SAME managed compile
version already transitively present; no version/scope upgrade. Main schema,
license, global SDK/checkpoint serializer, runtime registry and activation code
are unchanged. See docs/conformance/source-import-export.md for the API.

Alias cycles, non-string mapping keys and nonfinite values cannot be represented
as JSON and refuse conversion. Custom/non-core tags refuse. Unknown aliases,
duplicate resolved keys, malformed core-tag content and unsupported YAML version
directives also refuse. The raw stream has a token bound before composition;
the expansion has separate node/character/depth bounds. The output stream stops
serialization at the byte limit and returns no partial bytes. Original UTF-8
bytes remain immutable and exactly exportable. Exact numeric-value comparison
does not coerce strings or use floating-point rounding.

Core resolution follows the official [YAML1.2.2 table](https://yaml.org/spec/1.2.2/#1032-tag-resolution).
Finite decimal/octal/hex numbers, core null/boolean and explicit core tags are
covered; legacy numeric/boolean/date spellings keep their core string meaning.
Tests also exercise cyclic/undefined aliases, duplicate alias keys, exponential
node expansion, large shared-scalar expansion, directives inside quoted text,
oversized numeric tokens and output-limit interruption. This is an evidenced
JSON-compatible core subset, not a full YAML parser certificate or a Java-tag
adapter. Other syntax/schema and SDK boundaries remain explicit.

## Actual alias meaning and SDK evidence

The corpus now keeps ALL42 originals and converts39 accepted sources into both
formats (78 outputs); only the three invalid schema controls refuse. Eight
released-declared variants use the actual RELEASED_1_0_1 profile, the remaining34
sources the DEVELOPMENT_1_0_3 profile. Each report row records that profile.
The alias source now succeeds with the referenced value expanded; the original
anchor/comment bytes remain retained. Presentation notices mention alias-name
loss. The initial7f6e622 alias refusal and its evidence remain historical.

An ACTUAL fixed set-only SDK workflow executes the original alias YAML and the
codec-materialized JSON separately. Original output is
`{first:{label:example},second:fixture}`; materialized output is the expected
`{first:{label:example},second:{label:example}}`. Actual outputs and expected data
are retained in target/conformance/source-codec/alias-sdk-observation.json.
The existing model assessment's parser-tree comparison therefore does not prove
alias semantics. Its two writer failures and context/fork findings stay visible;
no older report expectation was rewritten to hide the new alias finding.

Claude WP-601/control: preserve original source/hash/format/profile as before.
Review and bind any materialized executable view separately before publication;
do not assume the original aliased YAML executes correctly on the current SDK.
The one pure workflow probe is not permission, artifact approval, a full runtime
bridge or a substitute for actual storage/reference/publication consumers.
Request shared contract/schema work from GPT; no control code is edited here.

## Local verification and initial failures

Final local command, Java25/release21: Maven `-B -pl modules/oa-runtime -am test
-Dtest=WorkflowSourceCodecTest,OwsConformanceTest,OfficialCtkTest
-Dsurefire.failIfNoSpecifiedTests=false`. Actual26 codec plus unchanged10
conformance controls pass, zero failures/errors/skips. The full reactor
`mvn -B verify -DskipTests` packaging/dependency check also passes; it is not a
full local test run. Actual dependency lists retain SnakeYAML2.6 and networknt
2.0.8 compile, and dependency/license policy passes.

The first local run exposed an incorrect ScannerImpl token-consumption call
(23 errors). The scan now peeks/fills the lazy token queue before consuming it.
The next controls exposed Java inherited Resolver pattern names shadowing the
enclosing core patterns (one failure at binary-like 0b10). Explicitly qualifying
the core regexes fixes that; the default resolver's regex length cutoff is also
overridden so oversized numerics cannot silently turn into strings. Original
expected scalar meanings, rejection and expansion assertions remain intact.
Full local Windows runtime testing still has the separately reproduced Cedar
JNI limitation; current full runtime/provider proof must come from fresh Linux.

Tracking stays partial. Fresh exact Linux/BOTH CI, current peer and final PR
checks remain pending. No second GPT PR, activation, merge or acceptance claim.
