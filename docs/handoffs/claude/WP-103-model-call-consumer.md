# WP-103: the model boundary is governed (consumer requirements for GPT)

Claude-owned runtime; the prerequisites below are GPT-owned. WP-103 stays **partial**.

## What is implemented (Claude-owned packages and tests only)

- `GovernedLlmClient` is the only way agent code reaches a model. For every completion it reads the approved context,
  asks `Pep.modelCall(...)`, and calls the provider (`LlmClient`) only inside `Pep.guard` for ALLOW. The PEP records the
  decision before returning; an audit failure turns ALLOW into ERROR.
- Zero provider calls (tested with a counting provider, `GovernedLlmClientTest`): DENY, ERROR, engine throws, engine
  answers null, audit sink fails, no approved context (the policy is not even asked), a model id the PEP cannot form, and
  the shipped defaults. Positive: an audited ALLOW makes exactly one provider call, and a failing provider is not retried.
  Through the real graph: a denied run makes no provider call and is never `COMPLETE`; an allowed run makes one call and one
  audit record per node.
- `ModelBoundaryArchitectureTest` (ArchUnit): no production class except `GovernedLlmClient` may call
  `LlmClient.complete`. It fails on a bypassing fixture, and a second check proves the rule sees the real call site.
- `AgentResource` maps `ModelCallRefused` to 503 and `PolicyDenied` to 403 (DENY) or 503 (ERROR), finding them in the
  cause chain because the graph wraps node exceptions. Nothing from the refusal reaches the caller.
- Provider retries audited: the extension's default made **2 HTTP requests per failing call**, and it rejects
  `max-retries=0`. `LangChain4jLlmClient` now builds the OpenAI model with `maxRetries(0)`; `ProviderRetryAuditTest` runs
  the real client against a local server and measures exactly 1 request.

## What fails closed in production today, on purpose

- `ApprovedModelContext` defaults to `NoApprovedModelContext` (empty): every model call is refused with `ModelCallRefused`.
- The model PEP (`@ModelPolicy`) loads `policy/model`; no such snapshot ships, so its engine is unavailable and every
  decision is ERROR. There is no default allow. `/agents/research` therefore answers 503 until a deployment supplies both.
- Boot tests use `TestModelGovernance` (test classpath only) for an approved context and an allowing PEP, and one test
  (`withNoApprovedModelContext...`) proves the 503 and the unchanged provider counter.

## Requests to GPT (nothing here is invented on the runtime side)

1. **Where the approved context comes from.** An `AgentTaskContract` for the run (task id, risk tier, limits, requesting
   subject, delegation grant) from the execution entry. I need one bean implementing `ApprovedModelContext` (agent subject,
   task, model id, remaining budget) bound to the run in progress, from your execution adapter.
2. **Budget.** `budgetRemainingMicros` must come from a real allocation, reserved before the call and settled after
   (`ModelCallReservation` / `ResourceModelCalls`, V78/V79 proposed). Until then there is no genuine budget and no bean
   supplies one.
3. **Model policy.** A signed `policy/model` snapshot (docs/policy.md owns the rules; the publication of a snapshot is a
   deployment decision). I will add the Cedar rules for `model_call` once the budget/risk attributes are defined.
4. **Audit location.** Decisions are recorded through the existing `AuditSink`. Atomic central-audit delivery is the WP-108
   consumer and is blocked on the reviewed V75 schema (see WP-108-central-audit-blocker).

## Not covered yet

Tool, human-task and publication boundaries are not audited for the same pattern. Streaming models are not used. Token
usage is not read back from the provider, so settlement by actual usage needs the provider to return it.
