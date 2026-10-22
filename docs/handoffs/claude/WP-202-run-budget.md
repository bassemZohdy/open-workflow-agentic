# WP-202 handoff: in-memory run budget (Claude)

Status: implemented and tested on branch `claude/WP-202-run-budget`, stacked on #61 (WP-201 node interceptor). **No PR is open**: one implementation PR at a time. Open it after #61 merges (rebase onto main first).

## Behavior added
`RunBudget(ExecutionLimits, Clock)` enforces the three task limits for one run, by reservation before the work:
- `admitIteration()` numbers iterations from 1 and throws `BudgetExhausted(ITERATIONS)` past the limit;
- `reserveCost(micros)` takes the cost before a call and takes nothing when it does not fit (`COST`), so spent cost never passes the limit; reservations are not refunded when the call fails;
- both throw `BudgetExhausted(WALL_CLOCK)` once the wall-clock limit has passed;
- `costRemainingMicros()` is the value for `ApprovedModelContext.Approved#budgetRemainingMicros`; `exhausted()` reports the reached limit without changing the budget.
`NodeInterceptor` has a second constructor taking a `RunBudget`: each node attempt is an iteration, an exhausted budget refuses the node (zero runs) and records `agent.node.refused` with the limit name.

## Evidence
- `RunBudgetTest`: 10 tests (iterations, cost reservation takes nothing on refusal, never overspends, wall clock edges, zero/negative cost, 16-thread reservation test, interceptor integration).
- Mutation checks: removing the cost check failed 3 tests; disabling the clock failed 2; restored.
- Full `oa-runtime` suite: 454 tests, 0 failures/errors (PostgreSQL 16 running).

## Not done
- **Durable exhaustion decisions** (the WP-202 "durable" part): the budget is in memory, so a restart gets a fresh one. Needs a migration number and table from GPT.
- Token and concurrency budgets: the contract (`ExecutionLimits`) has iterations, wall clock and cost only; a token limit is a contract change, GPT-owned.
- Nothing calls `reserveCost` yet: the model boundary still takes `budgetRemainingMicros` from `ApprovedModelContext`, which production does not supply. Wiring needs the execution entry that creates the approved task.
- T06 is not automated or promoted.

## Next action
Open the PR after #61 merges; ask GPT for the budget-exhaustion table number and whether tokens belong in `ExecutionLimits`.
