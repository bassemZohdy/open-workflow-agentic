# Execution contract integration (2026-10-26)

GPT supplies the shared contract requested by the Claude execution proposal.
Baseline main72b4c01 includes V75/PR65 and the single retained-output fingerprint.
This increment extracts only ResourceExecutionEntry, ResourceExecutionOutcome and
ResourceExecutionFence from existing GPT preparation58e02fc. It does not merge
the older branch's schemas, task allocations, budgets, gateway changes or helpers.

## Contract and consumer obligations

The entry retains the complete ResourceCallScope and a positive execution epoch.
Its call key/thread derive from the original retained canonical scope; a new epoch
does not create a business occurrence. The trusted producer must recover that
original scope through the canonical occurrence lookup, not substitute a new SDK
entry counter. All fields and full owner kind/id remain data, never permission.

The outcome binds the exact entry, pinned saver/serializer profile, actual physical
run and checkpoint UUIDs, media and the shared stored-representation fingerprint.
requireMatches checks only data equality. Actual trusted read-only inspection,
terminal structure, bounded SQL retrieval, current authority and storage truth
remain consumer responsibilities; mutable or missing checkpoints refuse release.
There is no latest-checkpoint fallback or inference from a released thread.

The fence is a transient internal interface passed separately. Its implementation
must bind the exact entry and combine live registry/full owner/task/target/lease/
execution checks with Claude's verified signed expiry/grant/scope/PDP authority.
Refresh after blocking I/O, before fresh entry, on governed nodes and before private
output release. Do not accept implementations from workflow/request metadata or
serialize them; preserve interruption and the same private context across async
threads. Missing/closed context refuses. No implementation/default allow is added.

The later backend still owns one irreversible fresh entry acknowledgement and
callback. A constructed entry/fence, empty checkpoint store, changed credential,
expired lease or lost commit reply cannot authorize a second graph run. UNKNOWN
needs governed resolution. Current production legacy admission remains unwired;
there is no model/tool/graph callback, new schema or data-source activation here.

## Evidence

All200 contract tests pass locally, including four new entry/outcome cases. The
three existing fingerprint helper cases stay in their existing suite rather than
being duplicated. The three production contract files retain preparation bytes.
The existing actual ObjectStreamStateSerializer test also passes after checking
the outcome against actual stored Base64 and refusing changed serialized content;
it invokes no graph, database or Java deserialization.

Independent Python strict UTF-8/length-prefix/int64-big-endian/SHA256 vectors for
the fixtures in ResourceExecutionContractTest:

- call: rc1-82b4f65ae24469b721ff38a2e6118ac882784afa92d75c900b7fbaa6fbe77470
- entry: re1-74bd37f5edf459c81a934cedcc63338719956eea40414793a76456c7345d661f
- outcome: 1b54f7810ce583c28e331435c5622992bf93d2783ad238a777b469a15dc2327f

Exact Claude review, required current CI and merge remain pending. ADR0028 stays
Proposed; checkpoint adoption, V76 backend/current composite authority and later
producer delivery are separate ordered gates. No package/acceptance promotion.

## Integrated result

PR66 exact de853bbbad53cfb7f24cd8714fb3ff196b963270 merged as main
1773a00c219024d566453cc3918f24c6ec00aeb3 after Claude PASS6404473197 and full
40241496066. The independent reviewer reran contracts200, actual serializer1,
Python88 and generated checks, including independent Python vectors. Actual CI
logs confirm contract4, serializer1, negative default admission wiring1 and full
acceptance/dependency checks. Postmerge verification is pending. The next ordered
profile migration uses reserved additive V75_1; no execution backend or runtime
DDL/admission permission is activated by this contract integration.
