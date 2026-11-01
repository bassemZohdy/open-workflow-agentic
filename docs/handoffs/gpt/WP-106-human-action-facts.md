# WP-106: HumanActionFacts and the facts-aware PEP entry point

Status: implementation increment for review. A contract and an enforcement rule set; there is still no durable human-task
service, no assignment storage and no wakeup. WP-106 and T02 stay not started.

## What this adds

`HumanActionFacts` (oa-contracts, `governance`) is the immutable DATA snapshot the policy request in
[WP-106-human-policy-intake.md](WP-106-human-policy-intake.md) asked for: tenant, task id and version, status, due time,
optional agent task, initiator, assignee, risk tier and a `SeparationObligation` (`NONE` or `INITIATOR_MUST_NOT_ACT`).
Every fact a rule needs is mandatory, so a missing fact cannot become permission: no null initiator, no absent
obligation, no default risk. It carries no "authorized" flag, no policy answer and no capability. Only the durable
human-task record may produce one.

`Pep.humanTaskAction(actor, actorTenant, scopes, facts, action)` keeps every rule of the existing `HumanTask` overload
(person only, open or in-progress, action scope held, decide only by the assignee) and adds, before the engine and with
zero engine calls:

- the actor's tenant must equal the task's tenant (a missing actor tenant refuses);
- a task at or past its due time accepts no action;
- with `INITIATOR_MUST_NOT_ACT`, the initiator may neither `DECIDE` nor `ROUTE`.

The engine request carries the risk tier. No rule depends on the risk tier alone and no obligation is invented: a task
whose obligation is `NONE` is not restricted by the initiator rule, preserving the approved risk-policy distinction the
intake asked for.

15 DB-free tests cover each rule, the boundaries (due time exactly, one nanosecond before), the unchanged existing rules,
an engine DENY, and the mandatory-facts record.

## Decisions that need the product owner (not guessed here)

- The scope names `human-task:decide|route|cancel` are still the proposed, unapproved ones from the Claude handoff.
- `CANCEL` is exempt from the initiator rule so a person can withdraw their own request. Confirm that against the
  approved separation policy; if cancellation must also be separated, remove the `action != CANCEL` condition.
- The intake proposed a wider vocabulary (assign, delegate, reassign, approve, reject, escalate, cancel); the merged
  `HumanAction` is `DECIDE`, `ROUTE`, `CANCEL`. Reconcile before escalation (WP-109) can be built on top.

## Still open (GPT-owned)

- The durable human-task record (tenant, version, initiator, assignment provenance), its lookup and versioned,
  locked mutation, and the rule that the ALLOW is bound to the same facts after blocking PDP and audit work.
- Persisted decision identity, exact-retry and conflict behaviour, the pending wakeup and restart recovery.
- The acknowledged audit receipt and commit-uncertainty behaviour, and the full failure matrix of the intake handoff.
