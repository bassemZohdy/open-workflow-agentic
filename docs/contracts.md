# Contracts: the shared vocabulary (oa-contracts)

**Status:** WP-101 · **Date:** 2026-10-18 · **Requirements:** [M01](requirements/README.md), [M02](requirements/README.md), [M15](requirements/README.md) · **Decisions:** [ADR 0010](adr/0010-keep-agent-loops-in-langgraph4j-not-in-workflow-tasks.md), [ADR 0012](adr/0012-reach-models-only-through-an-llmclient-port.md), [ADR 0017](adr/0017-one-codebase-two-deployable-roles-with-enforced-boundaries.md), [ADR 0020](adr/0020-checkpoint-scoping-and-state-evolution.md), [ADR 0021](adr/0021-identity-delegation-policy-engine-and-secrets.md), [ADR 0022](adr/0022-protocol-choices-for-tools-and-agent-interop.md)

`oa-contracts` holds the types that every other package builds on: the agent's authority, decisions, effects, human tasks, evidence, the audit chain, and the ports. It is pure Java 21. It uses records, sealed interfaces and enums. It has no framework, no serialisation annotations and no compile-scope dependency. ArchUnit (`ArchitectureTest`) and `tools/check_dependency_policy.py` both enforce this.

Every record validates in its compact constructor: non-null, non-blank, bounded text, positive and capped limits, and immutable copies of collections. An invalid value cannot be constructed.

## 1. Identifiers

| Type | What | Invariant |
|---|---|---|
| `Ulid` (existing) | Sortable 128-bit id, 26 characters | Crockford base32, first character 0 to 7 |
| `ThreadId` | Checkpoint thread name, `ws1-` plus SHA-256 hex (68 characters) | Derived by `ThreadId.derive(tenant, user, workflowInstance)`. Each part is length-prefixed in a strict UTF-8 canonical form, so the derivation is injective over the tuple. Delimiter characters inside a part cannot make two scopes share a name (the collision the WP-004 spike found for `|`-joined ids). Unpaired surrogates are rejected. No Unicode normalisation: the exact stored text is the scope. |
| `Subject(kind, id)` | Actor: `USER`, `AGENT`, `WORKFLOW`, `SERVICE` | Id non-blank, at most 256 characters |

ADR 0020 decision 1 owns `ThreadId`. Ownership is not proven by the name: the runtime checks the `agent_task` row (WP-105) before any read, write or resume.

## 2. Agent authority (M02, ADR 0021)

**`AgentTaskContract`** is the approved authority of one task.

| Component | Rule |
|---|---|
| `id` | Mandatory |
| `goal` | Mandatory, non-blank, at most 4000 characters. Kept unchanged by narrowing: a different goal needs a new approval. |
| `permittedTools`, `permittedDataScopes` | Allow-lists of tokens (`[a-z][a-z0-9_.:-]{0,127}`), at most 100 entries each. **Default empty: nothing is permitted.** |
| `riskTier` | `LOW`, `MEDIUM`, `HIGH` |
| `limits` | `ExecutionLimits`, mandatory (below) |
| `requestingSubject` | The user whose authority the task uses |
| `delegationGrantId` | The grant that carries that authority (ADR 0021). Kept unchanged by narrowing. |

`permitsTool` and `permitsDataScope` answer one question each. Null, blank and unknown names give `false`. They are one input to the policy decision, not the decision itself.

**`ExecutionLimits`** bounds the agent loop (M02). Every value must be positive and within a named maximum:

| Limit | Range |
|---|---|
| `maxIterations` | 1 to `MAX_ITERATIONS` (1000) |
| `maxWallClock` | 1 second to `MAX_WALL_CLOCK` (24 hours) |
| `maxCostMicros` | 1 to `MAX_COST_MICROS` (10^12, that is 10^6 budget units) |

**`narrow(childId, tools, dataScopes, riskTier, limits)`** is the only way to derive a contract. It can only reduce authority:

| Part | Allowed change | Otherwise |
|---|---|---|
| Tools, data scopes | A subset of the parent's | `PermissionWideningException`, naming the extra entries |
| Risk tier | The same tier or a stricter one | `PermissionWideningException` (a tier may not be lowered) |
| Limits | Each at most the parent's | `PermissionWideningException` |
| Child id | Different from the parent's | `IllegalArgumentException` |

Goal, requesting subject and delegation grant are carried over unchanged. A chain of narrowings cannot regain a tool that an earlier step dropped.

**`AgentTaskRecord`** is the stored row of a task (WP-105 `agent_task`). It checks on construction that the task id is the contract id, the owner is the requesting subject, and the thread id is `ThreadId.derive(tenant, owner, workflowInstance)`. The version is for optimistic locking. `AgentTaskStatus` lists the lifecycle states.

## 3. Decisions (M12, M15, ADR 0021)

**`Decision(decidedBy, outcome, reason, policy, decidedAt)`** records one policy decision.

- `outcome` is `ALLOW`, `DENY` or `ERROR`. Only `ALLOW` permits (`allows()`, `DecisionOutcome.permits()`). `ERROR` is a refusal: no policy, an engine failure or a timeout.
- `policy` is a `PolicyRef(policyId, policySetVersion)`. The policy id is the platform's stable id. Engine-assigned names such as `policy0` are not stable and must be mapped first.
- `decidedBy` names the component that decided. The built-in rule for failures is `PolicyRef.FAIL_CLOSED` (`oa.fail-closed`, version `1`).

`AuthorizationRequest(subject, action, resource, taskId, context)` is the input to a decision. `ActionKind` is `TOOL_CALL`, `MODEL_CALL`, `DATA_ACCESS`, `EFFECT_COMMIT`, `RESUME`, `DELEGATE`, `HUMAN_TASK_ACTION`, `PUBLICATION` or `OPERATION` (WP-103 added the last three). `taskId` is null only for requests made outside an agent task.

`PolicyContext(scopes, riskTier, budgetRemainingMicros, onBehalfOf, delegationGrantId, permittedByContract)` holds the facts a policy may read besides the request (WP-103). Every field is optional except `scopes`, which defaults to none; a null field means the fact does not apply to the call. The enforcement point fills it from the approved task, so the policy engine never reads the task store itself. The four-argument `AuthorizationRequest` constructor uses `PolicyContext.EMPTY`.

## 4. Effects (M03, M06, ADR 0020 decision 6)

**`Effect`** is one row of the effect ledger: `threadId`, `nodeId`, `idempotencyKey`, `target`, `state`, `detail`, `recordedAt`. Its key is `EffectKey(threadId, nodeId, idempotencyKey)`.

**`EffectState`** and the allowed moves (`canMoveTo`):

| From | To |
|---|---|
| `INTENDED` | `CONFIRMED`, `FAILED` |
| `CONFIRMED`, `FAILED` | `COMPENSATED` |
| `COMPENSATED` | none (final) |

`FAILED` can still be compensated, because a call that reports failure may have applied part of its change. An effect in `INTENDED` is unresolved (`isResolved()` is false). After a crash the reconciler resolves it; it is not retried under a new key.

## 5. Human tasks and evidence (M05, M15)

**`HumanTask`** is work a person must do before the agent or workflow continues. The record requires an assignee for `IN_PROGRESS` and `COMPLETED`, and a due time after creation when one is set. `agentTaskId` is null when a workflow raised the task.

**`Evidence`** references what an agent step relied on. It holds a reference (a URI or store key, at most 2048 characters), the lower-case SHA-256 of the content at capture time, and the kind (`TOOL_RESULT`, `MODEL_OUTPUT`, `SOURCE_EXTRACT`, `HUMAN_DECISION`, `POLICY_DECISION`). Sensitive content stays where it is and is referenced here.

## 6. Audit chain (M15)

**`AuditEvent`** has an id, a 1-based `sequence`, `occurredAt`, `actor`, `action`, `resourceType`, `resourceId`, non-sensitive `attributes` (at most 32 entries), `previousHash` and `hash`. The record checks the shape of the hashes. Whether they are correct is decided by `AuditHash`.

**`AuditHash`** computes SHA-256 over a canonical, length-prefixed encoding of every field except `hash`. The first event links to `AuditHash.GENESIS` (64 zeros). `seal(event)` returns a copy with the right hash.

`verifyChain(events)` returns `ChainVerification(valid, firstInvalidIndex, reason)`. It detects:

- a changed field, or a hash that does not match the content;
- a rewritten event that is recomputed alone (the next event's `previousHash` no longer links);
- a removed, inserted or reordered event (sequence and links break).

What it cannot detect alone: a full rewrite of the chain from some point, including the head. `verifyChain(events, expectedHead)` detects that, and also a truncation, when the head is kept outside the log. The head anchor is part of the audit work (WP-111).

## 7. Artifacts (M09, M10, ADR 0020 decision 4)

`ArtifactRef(kind, name, version, sha256)` pins one version. `ArtifactKind` is `WORKFLOW_DEFINITION`, `AGENT_GRAPH`, `PROMPT`, `POLICY_BUNDLE`, `TOOL_CONTRACT` or `MODEL_BINDING`. Running work records these references so a new release starts new threads only.

## 8. Ports and failure semantics

Ports are interfaces in `...contracts.port`. Their Javadoc states the failure semantics, and the tests in `PortContractTest` and `FailClosedPolicyDecisionPointTest` check them against in-memory doubles in test sources. The doubles prove that the ports can be implemented. They are not shipped.

| Port | Operations | Failure semantics (fail closed) |
|---|---|---|
| `PolicyDecisionPoint` | `decide(AuthorizationRequest) → Decision` | Only `ALLOW` proceeds. Denial is `DENY`. No policy, parse error, engine exception, non-boolean result, timeout, `null` or a thrown exception is `ERROR`, never an allow. `FailClosedPolicyDecisionPoint` enforces this around any implementation: it runs the delegate on a virtual thread with a deadline (at most 5 minutes), maps `null`, exceptions, timeouts and interrupts to `ERROR` attributed to `PolicyRef.FAIL_CLOSED`, and copies only the exception class name into the reason. A timed-out delegate is not stopped. |
| `ToolGateway` → `ToolResult` | `invoke(ToolCall)` | Returns `Completed(output)`, `Rejected(decision)` or `Failed(reason, effectMayHaveHappened)`. A tool outside the contract, a DENY or ERROR, or an unknown task is `Rejected`, and the tool is not called. A timeout or crash is `Failed`. For a tool that changes an external system, `effectMayHaveHappened` is true and the effect must be recorded as unresolved. A rejection never carries an ALLOW. |
| `ModelGateway` → `ModelResult` | `complete(ModelCall)` | Returns `Completed(text, costMicros)`, `Rejected(decision)` or `Failed(reason)`. Model not allowed, DENY or ERROR, or budget exhausted is `Rejected`, and no call is made. A provider failure is `Failed`. Text is never returned after an error, and the gateway never switches model silently. |
| `EffectLedger` | `recordIntent`, `recordOutcome(key, state, detail)`, `findUnresolved(threadId)` | If `recordIntent` fails, the target must not be called. If `recordOutcome` fails after the call, the intent stays `INTENDED`, and the external call is not repeated under a new key. Same key with a different target, or a move the state machine forbids, is `IllegalStateException`. An unknown key is `NoSuchElementException`. Repeating an intent or an outcome is idempotent. |
| `TaskStore` | `insert`, `find(taskId)`, `findByThread(threadId)`, `replace(next, expectedVersion)` | A missing row is an empty optional. The runtime then refuses the thread and never creates a row to fill the gap. A duplicate task id or thread id is `IllegalStateException`. `replace` is optimistic: a version mismatch or a skipped version is `IllegalStateException`. An unavailable store throws, and callers must not read an exception as "no task". |
| `ArtifactRegistry` | `activeVersion(kind, name)`, `version(kind, name, version)` | An artifact that is not approved, or unknown, is empty, and work that needs it is refused. An unavailable registry throws; the runtime keeps running pinned versions and starts no new work that needs an unverified one. A stale answer past the freshness limit is empty, never guessed. |

## 9. Wire form and JSON Schemas

The schemas are in `oa-contracts/src/main/resources/schemas/`, Draft 2020-12, one self-contained file per type with `additionalProperties: false`:

| Schema file | Record |
|---|---|
| `agent-task-contract.schema.json` | `AgentTaskContract` |
| `decision.schema.json` | `Decision` |
| `effect.schema.json` | `Effect` |
| `human-task.schema.json` | `HumanTask` |
| `audit-event.schema.json` | `AuditEvent` |
| `evidence.schema.json` | `Evidence` |

Wire notes: ids are ULID strings in upper case; `ThreadId` is a string; instants are RFC 3339 strings; `ExecutionLimits.maxWallClock` is written as the integer `maxWallClockSeconds`; `Subject` is `{kind, id}`. Nullable fields are present with `null`, not omitted. The main code does not serialise. The wire form is fixed by the schemas and checked by `SchemaContractTest`, which writes samples with a test-side mapper (`WireFormat`), checks that they validate, and checks that each invalid case is rejected. It also checks that each schema's properties match its record components, so the two cannot drift apart silently.

The schema cannot express two rules: a due time after creation (`HumanTask`) and the hash chain (`AuditEvent`). The record and `AuditHash` enforce those.

## 10. Verification

| Test | Covers |
|---|---|
| `ArchitectureTest` | Contracts depend on the JDK only (rule and violating fixture) |
| `ThreadIdTest` | Derivation shape, collisions with `|`, `-`, `:`, length prefix, exhaustive delimiter tuples, strict encoding |
| `CanonicalEncoderTest` | Encoding layout, null versus empty, strict UTF-8, SHA-256 known answer |
| `AgentTaskContractTest`, `ExecutionLimitsTest`, `AgentTaskRecordTest` | Allow-lists default empty, bounds, copies, every widening case, record invariants |
| `AuditHashTest` | Intact chain, changed field, recomputed event, removed, inserted and reordered events, truncation against a head, full rewrite |
| `GovernanceRecordsTest` | Decision attribution, effect state machine, human task and evidence invariants |
| `FailClosedPolicyDecisionPointTest` | Pass-through of ALLOW and DENY; throw, null, slow, interrupt and `Error` become `ERROR` |
| `PortContractTest` | Port semantics with in-memory doubles and a guarded gateway |
| `SchemaContractTest` | Samples validate; invalid documents are rejected; schema properties match the records |

## 11. Not in this package

- The reconciler, the `agent_task` table and the ownership check (WP-105).
- Persistence of the effect ledger and the audit log, and the external head anchor (WP-108, WP-111).
- The policy-id map and version recording in a real engine (ADR 0021, marked unverified there).
- The tool and model gateways beyond the port contracts (WP-204, WP-203).
- The model port is named `ModelGateway` here, as in the WP-101 deliverables. ADR 0012 calls it `LlmClient`; the two names describe the same boundary. Renaming it is a decision for the owner.
