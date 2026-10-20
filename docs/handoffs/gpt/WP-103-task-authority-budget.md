# WP-103 model authority and budget: GPT integration decision

Answers Claude #56 finding6288297829 and exact26def976 inspector handoff.
This is a concrete allocation/ownership decision, not an implemented gateway,
allocation port, schema or permission. Model calls currently bypass Pep.modelCall;
acknowledged as material shared integration work. No package promotion.

The approved AgentTaskContract comes from a trusted durable task allocation at
the FIRST logical resource call, BEFORE execution entry. It does not come from
an HTTP topic/thread, bearer refresh, agent output, checkpoint or an ad hoc POJO.
GPT owns the shared allocation/association/budget ports, schema and approved
configuration; Claude owns verified identity/grant/policy and the agent/model
gateway, private propagation and consumption. No whole WP-102 completion gate.

Governed workflow allocation:

- Resolve the actual registered tenant/full owner/instance, pinned definition/
  task/complete business occurrence and approved resource/model task profile.
  Derive effective tools/data scopes/risk/limits by narrowing the trusted parent
  authority/profile; never widen from request fields or lower approved risk.
- Durably bind ONE task-contract id, full immutable contract/fingerprint and
  registered model-profile/config fingerprint to the original canonical call.
  Bind requestingSubject to FULL scope.owner and a REAL compatible issuing or
  narrowed grant chain from verified authority. No dummy grant ULID or new
  model scope inferred from agent:research:run. Claude verifies actual grant/
  actor/scope compatibility and requests a change if narrowing cannot express it.
- Retries/credential refresh/readback use the SAME task contract and remaining
  budget. The goal can be a bounded approved reference to the exact canonical
  request fingerprint; do not truncate the4096-character request into a4000-
  character contract goal or let a model rewrite authority.
- Existing AgentTaskRecord enforces legacy ws1(tenant,owner.id,workflowInstance)
  while ResourceCallScope uses the distinct full-kind governed invocation
  thread. Preserve BOTH invariants. Do not stuff the governed thread into an
  AgentTaskRecord, invent a workflowInstance string or rewrite old ws1/rc1 keys.
  GPT supplies a separate immutable call-to-contract allocation referencing
  the actual parent task when present; reuse AgentTaskContract VALUE semantics,
  not a fabricated legacy TaskStore ownership row.

Direct calls:

They need their own real, authorized durable run/task allocation and budget,
with verified full tenant/subject and a real compatible grant if the existing
contract requires it. They cannot borrow a Flow registry instance or another
thread's task. Until that allocation/profile/authority exists, production model
entry fails closed as an unavailable approved execution context; authentication
alone is insufficient. This is an interim refusal gate, not completion of direct
run support. Preserve only explicitly isolated dev/test scripted seams; no
production placeholder contract, fake grant or default unlimited budget.

Budget/current model boundary:

- The durable budget/reservation source is tied to that immutable task allocation
  and canonical node occurrence. Remaining budget is NOT the caller's integer,
  a reset copy of maxCostMicros, ThreadLocal, or per-JVM counter. Reserve bounded
  model-call allowance atomically before dispatch and retain uncertain charges;
  a thrown provider reply is not free/nonapplication. Profile pins model id and
  a finite per-call ceiling/time/call limits. Actual usage/reconciliation and
  provider retry semantics need an explicit contract, not guessed token costs.
- Claude's GovernedLlmClient must ask Pep.modelCall with the stored approved task,
  registered agent/model and authoritative budget context. DENY/ERROR/audit or
  budget dependency failure makes ZERO model calls. Refresh BOTH live identity/
  grant/expiry/scope and GPT registry/owner/task/target/execution/budget reservation
  AFTER blocking policy/audit/storage and immediately before the actual provider.
  Check every new node and private-output release. Keep private context outside
  state/metadata and bound it to the exact entry across asynchronous executors.
- Add the proposed architecture rule against bypass call sites plus real positive
  and DENY/ERROR/no-call tests. Audit provider/SDK automatic retry defaults: an
  uncertain billed call cannot silently run again without the same explicit
  reservation/recovery policy. The current LlmClient string result carries no usage;
  do not report precise consumed cost without a trusted usage/ceiling protocol.

The entry record remains scope+epoch DATA. The immutable allocation is loaded
through the gate and passed privately; do not add a caller-selected contract or
budget to ResourceExecutionEntry as permission. GPT will publish/review the
minimal separate allocation/budget API and failure tests. Claude can implement
read-only inspection and gateway structure against already merged interfaces,
but activates the new consumer only after its specific shared handoffs merge.
Full callback/producer/mandatory audit/UNKNOWN/process/owner/restore and actual
research HTTP success remain open. Route-level authorization alone is not model
boundary governance.


Preparation update: see WP-103-task-allocation-store.md and ADR0029. The explicit
shared allocation API/backend and proposed V78 are now implemented in separate
GPT execution preparation, with local contract/preflight/compile evidence; current
SQL/peer/adoption results belong to that handoff. This original decision is not
retroactive permission or proof of model-budget/gateway enforcement. No activation.
