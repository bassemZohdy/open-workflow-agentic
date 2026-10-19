# Policy enforcement points (WP-103)

**Status:** Implemented in `oa-runtime`, package `io.github.bassemzohdy.openworkflow.agentic.runtime.policy` · **Requirements:** [M12](requirements/README.md) (with M20, M26, M27 for the outage and containment behaviour) · **Decisions:** [ADR 0021](adr/0021-identity-delegation-policy-engine-and-secrets.md), [ADR 0017](adr/0017-one-codebase-two-deployable-roles-with-enforced-boundaries.md) · **Evidence:** [WP-006 spike](spikes/WP-006-identity-policy-secrets.md)

This document describes the policy enforcement point (PEP), the Cedar decision point behind it, the snapshot that the decision point evaluates, the safe-recovery path, and what is still open. Test status is not claimed here: it is generated from the build into `test-status.json`.

## 1. The model

Every boundary where the runtime lets something happen asks the PEP first. The PEP builds the request from the approved contract and the call, asks the policy decision point (PDP) through a fail-closed wrapper with a 64-call bulkhead, records the decision as the shared `AuditEvent`, and only then releases the call.

| Boundary | Entry point | Request resource | Action kind |
|---|---|---|---|
| Tool call | `Pep.toolCall(agent, contract, tool, budgetMicros)` | `tool:<name>` | `TOOL_CALL` |
| Model call | `Pep.modelCall(agent, contract, modelId, budgetMicros)` | `model:<id>` | `MODEL_CALL` |
| Human-task action | `Pep.humanTaskAction(actor, scopes, task, verb)` | `human-task:<taskId>:<verb>` | `HUMAN_TASK_ACTION` |
| Publication or activation | `Pep.publication(publisher, scopes, artifactRef)` | `artifact:<kind>/<name>@<version>#<sha256>` | `PUBLICATION` |
| Operator recovery | `Pep.operation(op, operator, scopes, taskId)` | `operation:cancel`, `operation:pause`, `operation:drain` | `OPERATION` |

**Non-bypass is a convention for now.** Nothing yet prevents a caller from invoking a tool without the PEP. The architectural rule (every route goes through the PEP) is open work (section 8).

### Guarding a call

`Pep.guard(decision, supplier)` runs the supplier only when `decision.allows()` is true. Any other outcome, DENY or ERROR, throws `PolicyDenied`, which carries the refusing decision. The call is never made.

## 2. Request shape

`AuthorizationRequest(subject, action, resource, taskId, context)`, defined in `oa-contracts`:

- `subject`: the actor (`Subject(kind, id)`), for example the agent acting for a user, or the operator.
- `action`: an `ActionKind`.
- `resource`: a string, at most 512 characters.
- `taskId`: the agent task, or null outside a task (drain).
- `context`: `PolicyContext(scopes, riskTier, budgetRemainingMicros, onBehalfOf, delegationGrantId, permittedByContract)`. Every field is optional except `scopes`, which defaults to empty. A null field means the fact does not apply to the call.

The enforcement point fills the context from the approved `AgentTaskContract`: scopes are the task's data scopes, the risk tier is the task's tier, the budget is the remaining cost budget, the user and grant are the task's requesting subject and delegation grant. `permittedByContract` is true or false from the tool allow-list, and null for model calls, which have no allow-list.

The Cedar adapter maps the request to a principal typed by subject kind (`User`, `Agent`, `Workflow`, `Service`), an `Action::"<kind in lower case>"`, and a `Resource::"<resource>"`. The context carries the facts under fixed names: `scopes`, `riskTier`, `budgetRemainingMicros` (-1 when unknown), `onBehalfOf`, `onBehalfOfKind`, `delegationGrantId`, `permittedByContract` (false when unknown) and `taskId`. Unknown text is the empty string. Resource strings are passed as Cedar values, never spliced into policy text, so a crafted name cannot match a different resource (tested).

## 3. Decision shape

`Decision(decidedBy, outcome, reason, policy, decidedAt)`, defined in `oa-contracts`:

- `outcome`: `ALLOW`, `DENY` or `ERROR`. Only ALLOW permits. ERROR is a refusal, and it is never an allow.
- `decidedBy`: `cedar-embedded` for policy decisions, `fail-closed` for failures wrapped by `FailClosedPolicyDecisionPoint`, `pep` when the PEP itself refuses, `safe-recovery` for the recovery rule.
- `policy`: `PolicyRef(policyId, policySetVersion)`.
  - `policyId` is the stable `@id` annotation of the deciding Cedar policy. Cedar's own names (`policy0`, `policy1`) change when a file is reordered, so the adapter maps them to the annotation through `Policy.getAnnotation("id")` before recording. A reordering test confirms the ids stay stable.
  - `policySetVersion` is the version of the snapshot the decision was made against.
  - Built-in reasons: `oa.default-deny` when no policy matched, `oa.fail-closed` for a failure with no snapshot loaded, `oa.safe-recovery` for the recovery rule.
- `reason`: at most 1000 characters. It names the deciding policy ids but never the request text or engine exception messages (only the exception class name is kept).

### Decision precedence

A forbid overrides a permit. Cedar's default is deny. The adapter adds two rules:

- If any policy in the evaluated set raised an error, the result is ERROR, even when a permit also matched.
- An ALLOW without a permit policy, or an engine name that is not in the snapshot, is ERROR.

## 4. Snapshot lifecycle

A snapshot is a set of Cedar texts plus a version and a content hash.

- **Hash.** `SHA-256` over a canonical, length-prefixed encoding (`CanonicalEncoder`) of the domain tag `oa.policy-snapshot.v1`, the version id, the number of texts, and each text in order. A `PolicySnapshot` can only be constructed when its declared hash matches. Changing any text, the version, or the order changes the hash.
- **Source.** `PolicySnapshotSource` is a port. `ManifestPolicySnapshotSource` reads a folder (disk or classpath) with a `snapshot.properties` manifest:

  ```properties
  version=2026-10-18.1
  sha256=<64 lower-case hex>
  policies=tool-calls.cedar,operations.cedar
  ```

  File names must match `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\.cedar`, so the manifest cannot point outside its folder. Text is decoded strictly as UTF-8. Size limits apply to the manifest (16 KiB) and each policy file (1 MiB).
- **Refresh.** `CedarPolicyDecisionPoint.refresh(source)` reads the source, compiles it, and swaps it in atomically. It never throws. The outcome is `Accepted(version, hash)` or `Rejected(reason)`.
- **Accepted only if** the source is readable, the hash matches, every text parses, every policy has a unique well-formed `@id`, and there are no templates. A rejected snapshot leaves the previous one in force.
- **No snapshot ever accepted.** Every decision is ERROR, and only the recovery operations in section 6 can proceed.
- **Atomicity.** One reference holds the active snapshot and its compiled form. A decision reads that reference once, so it sees one complete version. A test swaps versions 200 times while four threads decide, and every decision matches the version it names.
- **No caching beyond the snapshot.** The adapter keeps no decision cache. A revoked or tightened policy takes effect on the next decision after the next accepted refresh.

## 5. Failure semantics

| Condition | Result |
|---|---|
| No snapshot ever accepted | ERROR, `oa.fail-closed` attributed to the snapshot version (none) |
| Snapshot source unreadable (missing folder, I/O error, oversized file, non-UTF-8) | Refresh rejected; previous snapshot stays in force |
| Snapshot tampered (hash mismatch) | Refresh rejected with a reason naming the hash; previous snapshot stays |
| Text does not parse, policy without `@id`, duplicate or malformed id, template | Refresh rejected; previous snapshot stays |
| Cedar evaluation error (for example a missing context attribute) | ERROR, even when a permit also matched |
| Engine throws, or `LinkageError` from the native library | ERROR |
| Engine slower than the timeout (default 1 s, at most 5 min) | ERROR (`FailClosedPolicyDecisionPoint`); the slow call is abandoned, not stopped |
| 64 policy evaluations already in flight | Immediate ERROR from the PEP bulkhead; the delegate is not called. The global decorator supports bounds up to 1,024, while the PEP explicitly selects 64. |
| Engine returns null | ERROR |
| Audit sink throws while recording | The decision becomes ERROR, so a call is never released without a record |
| No policy matches | DENY, `oa.default-deny` |

## 6. Degraded mode and safe recovery

When the main decision is ERROR for an `OPERATION` request, the built-in `SafeRecovery` rule decides instead. It reads no snapshot, calls no engine and needs no database.

**Allowed in degraded mode**, all of the following at once:

1. The action is `OPERATION` and the resource is one of `operation:cancel`, `operation:pause`, `operation:drain`.
2. The subject is a `USER`. An agent, workflow or service never operates the runtime.
3. The context holds the scope `oa.recover`. The rule trusts the scopes it is given, so the caller must take them from the operator's verified token.
4. `cancel` and `pause` name an agent task. `drain` names no task, because it applies to the runtime.

Each operation stops or holds work and never starts or widens it. Cancel stops one task. Pause holds one task at its next checkpoint. Drain stops accepting new work and lets in-flight work finish.

**Not allowed in degraded mode**: everything else. Tool calls, model calls, human-task actions, publications, resumes, delegations, effect commits and data access are all refused. An agent or a service cannot use the recovery path, even with the recovery scope. An unknown operation is refused.

**A deliberate DENY is final.** If the main policy answers DENY, the recovery rule is not consulted. An operator without the scope gets DENY, not a recovery allow.

**Audit.** The policy package uses the same `io.github.bassemzohdy.openworkflow.agentic.runtime.audit.AuditSink` port as every other audit producer. `Pep` converts each policy decision to an `AuditEvent` (`policy.decision`); stable policy metadata and the recovery-path flag are attributes; request context and free-form decision reasons are omitted. A recovery allow is written as a second event with `policy.recoveryPath=true`, after the main ERROR it followed. If the audit write for the recovery decision fails, the operation is refused.

## 7. Latency

Measured with the method of the WP-006 spike: 2,000 warm-up calls, then 10,000 timed calls alternating allow and forbid. Times are per call on the caller's side. The host is a shared sandbox with no container runtime, so tails vary between runs. Use the numbers for comparison.

Final run on the WP-103 branch (`verify2`, full build):

| Path | p50 | p90 | p99 |
|---|---|---|---|
| Cedar engine alone (`CedarPolicyDecisionPoint.decide`) | 487.6 µs | 955.1 µs | 8.6 ms |
| PEP (fail-closed wrapper, audit sink, request build) | 539.4 µs | 1073.5 µs | 5.5 ms |
| Snapshot install (compile and swap), 200 calls | 988.8 µs | 9.3 ms | 61.2 ms |

The WP-006 spike measured Cedar at 387 to 525 µs p50 and 4.5 to 6 ms p99 in three runs. The p50 agrees; the p99 here is higher, because the build ran other tests in the same JVM. The numbers are not a performance budget.

## 8. What remains

**For WP-102 (identity and delegation):**
- The `delegationGrantId` and `onBehalfOf` context fields come from the approved contract. Verifying that the grant is current, unexpired and not revoked is WP-102's job, and the PEP does not check it.

**For WP-204 (tool gateway and MCP):**
- The tool gateway must call `Pep.toolCall` before every invocation, and must use `Pep.guard` (or check `allows()`) before calling the tool. Nothing yet enforces this.
- Non-bypass: an ArchUnit rule that tool and model implementations are reachable only through the PEP, plus a runtime check. Open (WP-006 open question 7).
- Default deny for uncovered HTTP paths (WP-006 Q16) is still unverified.

**For WP-111 (audit):**
- The PEP and other runtime components share one `runtime.audit.AuditSink` port. WP-111 supplies the hash-chained durable implementation; a local logging sink remains available for development and is neither durable nor tamper-evident.

**For WP-304 (separation of authority and outage safety):**
- The snapshot source is a port. The production source (the approved-state store the control side publishes to, with a freshness policy) is not built. The file and classpath sources are for development and tests.
- Freshness: the runtime keeps the last good snapshot without a limit. M27 requires a bounded freshness policy, so that operations needing fresh authorization fail closed after a limit. Not built.

**For WP-305 (containment) and T23:**
- Emergency revocation of a named agent, model or tool binding across active and waiting tasks, with incident recording. Not built.

**Other open items:**
- Policy-id map: the mapping from Cedar names to `@id` is built into each snapshot compile. Its behaviour across Cedar upgrades is not tested.
- The native library: cedar-java's uber jar is about 28 MB with native libraries for five platforms. Container images need a platform matrix (WP-403).
- Licences: Guava and the Cedar jar are checked by the dependency policy. The `jne` licence is recorded as `Apache 2` in its parent POM; the policy script now recognises that name, with a test.

## 9. Test coverage

| Test class | What it covers |
|---|---|
| `PolicyContextTest` | Context defaults, scope tokens, budget bounds, the four-argument request constructor |
| `PolicySnapshotTest` | The documented hash layout, sensitivity of the hash, version format, immutability |
| `ManifestPolicySnapshotSourceTest` | Directory and classpath sources, name refusal, strict UTF-8, size limits, missing keys |
| `CedarPolicyDecisionPointTest` | Attribution by stable id, forbid precedence, default deny, evaluation error as ERROR, rejected snapshots, template and id rules, revocation and tightening on the next decision, atomic swap under concurrent decisions, quoting in resource names |
| `PepTest` | Request construction at each boundary, `guard`, refusals from throw, null and timeout, audit of every decision through the shared event port, explicit one-call bulkhead behavior, audit failure refusing a release |
| `DegradedModeTest` | Exactly which operations are allowed with the main policy unavailable, the agent-is-never-operator rule, DENY finality, audit ordering |
| `PolicyRevocationAndOutageTest` | Revocation on the next decision, control-source outage keeps the last snapshot, rejected refresh never rolls back a revocation, tamper rejection, no snapshot ever loaded, operator-only stop during outage |
| `PepLatencyTest` | Latency at the three measured paths (printed, not asserted) |
| `PolicyArchitectureTest` | The policy package does not depend on any framework, with a violating fixture |
| `CheckDependencyPolicy` tests (`tools/`) | Licence normalisation, including `Apache 2` inheritance |
