# WP-106 human-action policy and durable-decision handoff

Requester/integration owner=GPT. Evidence reviewed at main
513b87820fb94acd31a110e3a8d3db1400fa6188. Verdict: interface intake complete;
implementation/activation HOLD for the explicit contracts below. This is a
cross-owner request, not an implemented service or peer approval. WP-106/T02
remain not-started. Main tracking and the c9ce389 membership review head stay
unchanged; this preparation opens no second implementation PR.

## Existing interfaces and the missing facts

The plan permits consumers of merged evidenced caller/PEP interfaces while
WP-102/WP-103 remain partial; there is no whole-package closure prerequisite.
Current sources nevertheless do not establish the complete human boundary:

| Main source | Available interface | Limit relevant to WP-106 |
| --- | --- | --- |
| `runtime/api/caller/CallerAuthority.java` | Verified token, tenant/subject/scopes, signed-expiry recheck after blocking work | Own-token admission does not itself require SubjectKind.USER or approve a human task |
| `runtime/policy/Pep.java`, `humanTaskAction` | Audited, bounded typed HUMAN_TASK_ACTION decision, task/resource/action attribution | Context carries actor scopes but no retained tenant, initiator, assignee, assignment/version or separation obligation |
| `contracts/governance/HumanTask.java` | Task id, optional agent task id, title/instructions, status/assignee, creation/due time | No tenant, workflow wait/occurrence, initiator, version, approved risk-policy pin or decision identity; assignee Subject is not restricted to USER |
| `contracts/governance/PolicyContext.java` | Scopes, risk, budget, on-behalf-of, grant and permitted-by-contract fields | No human-specific assignment/separation/current-version facts |
| `runtime/policy/PolicyFixtures.java` (TEST ONLY) | Reference `human-task.permit-assignee` permits User with `tasks:act` | Despite its name, the fixture does not compare an assignee or initiator; it is not an approved production human-action rule |

Inventory of shipped `src/main/resources/policy` finds the operations snapshot,
not a human-action policy. There is no active WP-106 service to classify as a
bypass here. A generic scoped permit or a handcrafted CallerContext cannot be
substituted for M05's approved separation-of-duties policy.

## Concrete producer/consumer request to Claude

GPT owns the shared data/port/schema and human lifecycle implementation. Claude
owns the caller, PEP/Cedar mapping/rules, audit consumer and their tests. Please
confirm or amend the following fact contract before a human mutation adapter is
implemented; request the exact shared type/schema changes in your handoff.

The proposed PEP input is a purpose-specific immutable human-action snapshot,
alongside the admitted actor and verb. It must carry facts, not an `authorized`
boolean or a caller-supplied policy answer:

| Fact | Authoritative producer |
| --- | --- |
| Tenant, human task id/version, current status and due time | GPT durable human-task record, scoped lookup and locked/versioned mutation |
| Workflow instance/definition and exact wait/occurrence; optional agent task | GPT registry and retained wait binding, not a request body or visible thread name |
| Initiating subject and any consequential agent/on-behalf-of attribution | Retained trusted creation/approved task facts; not inferred from the current assignee |
| Current assignee and any business delegation/reassignment provenance | Retained human assignment facts, with explicit version and effective state |
| Risk tier and separation obligation/policy reference | Reviewed approved task/definition policy pin; an absent fact must not become permission |
| Acting subject, tenant, scopes, delegated/workload provenance and current expiry | Claude verified caller/authority boundary, never headers/body flags |
| Requested action | Closed action vocabulary agreed for assign/delegate/reassign/approve/reject/escalate/cancel; not arbitrary resource text |

`HumanTask` can remain a presentation value; do not overload its title,
instructions, agentTaskId or the generic `permittedByContract` flag to smuggle
these facts. The new snapshot is DATA, not a capability or proof of current
authority. GPT will implement shared types only after the mapping is agreed.

Please provide the precise production action/scope vocabulary, Cedar fact
mapping and separation rules. `tasks:act` above is a fixture observation, not a
newly approved product scope. Preserve the approved risk-policy distinction for
forbidden human initiators instead of inventing an unconditional all-risk rule.
Consequential agents cannot self-approve; an agent/workflow/service principal or
a technical delegated actor must not become the human approver by relabeling its
on-behalf-of user. Business delegation of an assignment is a distinct retained
decision, not the existing agent-token exchange grant.

## Mutation, audit and wakeup contract

For an allowed action, identity/tenant and current assignment/status/version/
deadline/wait ownership must still match AFTER blocking PDP/audit work and before
the durable mutation. CallerAuthority's signed expiry must be checked again.
The ALLOW decision must be bound to the same facts; a concurrent reassignment,
cancellation or expiry requires refusal/reload/reauthorization, never reuse of a
stale allow. Do not hold a database lock across unbounded remote policy work.

GPT will persist the human transition, original decision identity and pending
wakeup before a Flow listener can resume. Exact retries return the original
retained decision; changed actor/action/content/version under the same identity
conflicts. Restart recovery replays pending wakeups through the reviewed
ownership/checkpoint acknowledgement protocol, not by restarting a workflow.
The needed pinned listen/wait interfaces remain behind the pending #55 and later
reviewed delivery integration; proposed APIs are not merged prerequisites.

Every transition needs audit evidence. The ordinary AuditSink/Pep audit commit
does not make a separate human database write atomic. Claude should identify the
required acknowledged receipt/commit-uncertainty behavior against the existing
WP-108 source/draft/receipt and central append-once handoff. GPT owns any local
fact/outbox/schema integration. No eventual queue alone is decision authority,
and no actual central consumer or human transaction is claimed here.

## Required failure and concurrency proof

Before activation, both owners must independently verify these actual cases:

- Wrong tenant, missing caller, non-user/technical delegated actor, forbidden
  initiator or non-current assignee: zero mutation and zero listener wakeup.
- PDP DENY/ERROR/timeout/unavailable, mandatory audit failure or uncertain
  acknowledgement: no successful decision or fabricated receipt; explicit
  reconciliation where a real commit may have occurred.
- Signed expiry during blocking audit, reassignment/cancellation/expiry or task
  version/wait change during the decision: stale allow cannot mutate/wake.
- Concurrent approval/rejection: one committed terminal decision; no second
  contradictory event/wakeup. Exact replay reads the original decision and
  changed-content replay conflicts.
- Process death after durable decision and before wake/ack: original decision
  survives; only the pending correctly owned wait resumes after restart.
- Actual production PEP plus retained risk/assignment facts, including denial
  of forbidden initiator and consequential-agent self-approval, before T02 is
  marked automated. A permissive test bean is not that proof.

## Owned rollout and current evidence

No human schema version is allocated. GPT centrally sequences future additive
migrations after the current V71..V80/72.1 work; applied migrations and global
baseline settings stay untouched. Proposed runtime/human storage/service paths,
shared contracts and app lifecycle tests will be registered to GPT before code;
Claude changes its existing policy/caller/audit paths through an exact reviewed
handoff. No public endpoint or human console behavior is activated here.

Private human instructions/decision content stay in governed storage; audit
references and reports must not turn them into freely readable event payloads.
Retention, least-privilege deployment and cross-state restore proof are separate
open gates. No RPO/RTO, deployed grant or complete human workflow is invented.

Commands/results: read current TODO/source plan/M05/T02 and the listed production
sources; inspect shipped policy resources and the distinct test fixture. Scope
verdict is INCOMPLETE for executable WP-106 authority/wait/audit integration, not
a failure of the already evidenced generic caller/PEP interfaces. Only document
and generated-tracking checks apply to this handoff; no new execution tests or
acceptance promotion are claimed. The prior goal turn made progress (pushed
fingerprint/checkpoint proof), not a live-process wait or completion.
