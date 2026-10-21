# WP-106 handoff: human-action policy in the PEP (Claude)

Status: implemented locally, stacked on #58 (f459e06). Not pushed; it opens as the single Claude PR after #58 merges.

## Behavior added
- `HumanAction` closed vocabulary: `DECIDE`, `ROUTE`, `CANCEL`; required scopes `human-task:decide|route|cancel` (proposed, unapproved).
- `Pep.humanTaskAction(actor, scopes, task, action)` applies deterministic rules before the policy engine:
  - only `SubjectKind.USER` may act;
  - only `OPEN` or `IN_PROGRESS` tasks accept actions;
  - the action's scope must be present, and a scope for one action never covers another;
  - `DECIDE` requires the actor to be the task's assignee.
- A rule refusal is audited and returns DENY (`PolicyRef.FAIL_CLOSED`) with zero engine calls.
- If the rules pass, the engine decides. Engine DENY or error and audit-write failure all refuse.

## Evidence
- `PepHumanActionTest`: 10 tests covering each rule and the zero-engine-call cases.
- Mutation check: breaking the assignee rule failed 2 tests; restored.
- Full `oa-runtime` suite: 435 tests, 0 errors (PostgreSQL 16 running).

## Not done, needs another owner
- Tenant, initiator and risk rules need GPT's `HumanActionFacts`; they are not guessed here.
- Product owner must approve the scope names and the separation-of-duty policy.
- Checkpoint tables stay `createTables(true)` until GPT allocates the checkpoint migration number.

## Next action
GPT merges #58, then Claude rebases, re-runs the suite, pushes one PR and requests an exact-SHA review.
