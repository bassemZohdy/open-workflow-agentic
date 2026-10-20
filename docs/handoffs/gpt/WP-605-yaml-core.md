# WP-605 YAML core source semantics

Reviewer=GPT (author verification, not peer approval).
Reviewed SHA: **ce779d1a9a8be7d1247eb1a79bd34106d8b802c1**.
Verdict: bounded source/core API verified; **integration HOLD** for exact peer
and final required implementation-PR gates.
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

Tools94, actual dependency policy, source-regenerated tracking/check and authored
diff checks pass. Current peer/final implementation-PR checks remain required.
No second GPT PR, activation, merge or acceptance claim.


## Fresh exact-product Linux and real-provider evidence

All runs below are SUCCESS at the reviewed SHA, attempt1/no rerun. Full logs
were inspected. Every actual XML suite was checked for positive/consistent
counts, unique method names and zero failures/errors/skips. Actual ZIP bytes
match GitHub length/SHA-256 and pass CRC plus bounded safe-path checks.

- [Dedicated39471330241](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39471330241),
  job118795855130: contracts220/runtime380 including codec26/conformance10;
  all81 XML/600 tests inspected.
- [BOTH39471331117](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39471331117),
  standard118795865012/delegation118795865668: contracts220/runtime380/testkit196/
  app417 EACH, failsafe23/28; all164 XML per lane (1236/1241 tests) inspected.
  Actual standard3/delegation8/core1/audit-process4/HTTP7/manual-process8 EACH
  pass, all five strict pre/post markers present. Matching-profile checker reruns
  against both actual archives, with all four recovery flags, pass without skips.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| dedicated12287062914 | 844978 | 42d3763b48d75dd4cbac755665933e17d757faee24d69a1507270776226ced44 |
| standard12288475138 | 2627917 | a227ddad856fb0db1485b07494c215bdc27be110af8aa5d5e627525edfae76c1 |
| delegation12288300357 | 2626241 | 28bf292e2b1e7ac1e07ead41a7bd9dc7348c56163feafdd64c848039b01fc992 |

ALL three archives contain exact26 codec method names,42 originals matching
Gitce779d1 bytes AND their reported hashes, and78 converted files independently
reparsed and compared as typed data. The independent comparator explicitly
refuses bool/int/string coercion while comparing exact numeric values. Profiles
are exactly released8/development34; precisely three SCHEMA_INVALID controls
refuse and have no converted file. The alias fixture is now correctly expanded.
All three actual alias-sdk-observation files contain the exact wrong original
SDK output, correct materialized output and expected data described above.

All three also retain the unchanged official21-scenario report/11 observations,
and earlier42-source model report/48 outputs/74 successful SDK exports/two
writer diagnostics. The78 source-data conversions are distinct from the74 SDK
model serializations; no SDK failure is erased. Only the previously admitted two
wrong noncompeting-fork fingerprints are equivalent for regression comparison,
always DIVERGED, with actual output/order retained. Official CTK remains nine
MATCH/two DIVERGED among eleven executed, ten external scenarios UNMEASURED.

Both strict archives retain exact process8/storage26/contracts8/HTTP7/boundary31/
model24/allocation15 method sets, original14 audit/13 HTTP logs,32 manual worker
logs/eight original decisions/56 recorded backend observations each and the
correct halt/outcome/purpose matrices. No owned HTTP cleanup interruption occurs
in these two runs. Bootstrap LogManager warnings remain in raw logs. These are
current bounded DATA-fixture regression facts, not signed operator, production
model/authority or atomic audit-consumer proof.

Later evidence updates change only handoff/tracking/CHANGELOG. Code, resources,
schema, contract and acceptance trees stay unchanged from testedce779d1. #55 is
still frozen with no current Claude review; #53 d38776c/#56 26def97 are unchanged.
Durable authoring/approved materialization/reference/publication callers, broader
YAML/SDK/external CTK semantics, substitution and owner/peer/final PR gates stay
open. WP-605/H01/H02 partial, tracking11 completed/13 partial/29 not started,
acceptance3/39. No activation, second GPT PR, merge or status promotion.
