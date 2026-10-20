# WP-103: what a failing research node leaves (ResearchRunInspector)

Companion to the execution-adapter proposal (branch claude/WP-103-execution-proposal).


Measured with the real graph, a scripted model and the real checkpoint tables (`ResearchRunInspectorTest`):

- A node that throws leaves its thread **open** (unreleased) with the checkpoint written *before* that node: a failure in `plan` leaves one checkpoint (`__START__` → `plan`); a failure in `draft` leaves two (last `plan` → `draft`). Neither is complete.
- A completed run leaves one released thread whose last checkpoint is `review` → `__END__`; running the same name again adds a second thread row.
- So `inspect` reports NONE (no row), PARTIAL (one thread, last checkpoint not leading to `__END__`), COMPLETE (one thread ending at `__END__`, with a digest of that checkpoint's id and stored bytes), REPEATED (more than one thread row). A store that cannot be read is an error, never NONE.
- It is read-only: no graph call, no model call, no deserialization. PARTIAL is never resumed or re-run automatically; it stays UNKNOWN until the manual-resolution operation GPT defines.

## Boundary finding: model calls are not gated by the PEP (WP-103 remaining gate)

Found while reading the graph: `ResearchAgent` calls `llm.complete(...)` in all three nodes (plan, draft, review) with no
`Pep.modelCall` decision before them. `Pep.modelCall` needs an approved `AgentTaskContract` (task id, risk tier, budget), and
a direct or delegated `/agents/research` call has none, so the model boundary is currently ungoverned: the only checks are
the route admission (`agent:research:run`) and the output validator. `grep llm.complete` in `runtime` finds only these three
call sites, and `LangChain4jLlmClient` is the only `LlmClient` implementation.

This is a real bypass of the model boundary, not a test gap, so it is reported here and **not** hidden by a weakened test.
Closing it needs a decision that is not Claude's alone: where the approved task contract and budget for a governed run come
from (the Flow task / execution entry), which is GPT's shared contract area and the execution-adapter proposal. Planned shape
once that exists: a `GovernedLlmClient` that calls `Pep.guard(pep.modelCall(...), ...)` before each completion, an ArchUnit rule
that no class outside it may call `LlmClient.complete`, and tests that a DENY or ERROR decision makes no model call.
Tool, human-task and publication boundaries are not yet audited for the same pattern; WP-103 stays partial.
