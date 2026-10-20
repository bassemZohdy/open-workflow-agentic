# ADR 0033: Retain manual effect decisions atomically

Status: Proposed; trusted storage preparation, operator/authority/central/Flow gates remain.
Date: 2026-10-20. Owner: GPT; reviewer: Claude.

## Context

The manual-resolution queue retains uncertainty observations, not an operator's
decision identity, original evidence or policy attribution. A raw recordOutcome
call cannot prove a policy-verified manual decision. Regenerating an id after a
lost commit reply risks replacing a decision or changing its attribution. A
competing target reconciliation can also invalidate an operator's observed
history. Business history remains important after a registry run terminates.

## Decision

Add JDK-only EffectManualDecision DATA and explicit non-CDI trusted persistence
JdbcEffectManualDecisions. Persist one immutable decision per full effect key,
with a stable caller-retained ULID, full original scope/command, expected latest
journal id, explicit CONFIRMED/FAILED outcome, full operator, original policy
id/version and authorization/evidence SHA256 references. Use one strict canonical
fingerprint over the complete tuple. Do not store payloads, credentials, target
response bodies, unbounded explanations or a fabricated authorization token.

Record under the existing cross-process effect claim, then registry/ledger/queue
row locks and the explicit short transaction limits. Refuse legacy provenance,
changed full scope/request, unattempted/nonqueued/resolved intent, stale latest
journal and conflicting id/tuple. An exact historical retry returns the original
fact, including after later compensation, without another transition/source.

A bounded coherent find(full scope, full command, original id) returns historical
DATA with the original source checked. A trusted producer can recover its first
server-derived policy/evidence metadata without accepting replacement client
fields. It must compare original client intent and reauthorize the current caller
before exposing a result; find is neither mutation permission nor an allow cache.

Atomically commit decision, manual journal event, MANUAL source/original draft,
pending audit delivery, ledger outcome and queue closure. Proposed additive V80
extends V77 without rewriting it or any applied migration. Validate canonical
identity/scope/history on insert, require outcome/closed queue/source at deferred
commit, retain all parent facts and reject update/delete/truncate. Preserve the
operational system:effect-ledger audit actor; operator attribution is distinct
bounded manual.* metadata in the existing as1 source domain and shared content
fingerprint. No other transition's canonical draft changes.

CONFIRMED/FAILED requires authoritative evidence from the future trusted producer;
a timeout cannot choose FAILED. A manual decision can resolve a separate undo
intent, but does not itself mark the original COMPENSATED. That remains the
existing separately confirmed compensation/link protocol. Terminal registry status
does not erase business history; storage DATA does not authorize recovery there.

## Alternatives and consequences

Reusing raw recordOutcome loses stable operator/evidence attribution. Reusing
manual-queue observations conflates uncertainty with decisions. Mutating an old
decision or accepting a second decision id silently replaces the first outcome.
Requiring RUNNING for historical persistence loses unresolved post-termination
recovery data. Each is rejected. One immutable decision per key deliberately
requires a separately designed explicit correction protocol if evidence was wrong;
no overwrite/delete/automatic decision reset is provided.

The record, fingerprint, schema and storage return are DATA, never permission.
Before exposure, Claude must supply actual signed/current operator and fail-closed
OPERATION resolve-effect/oa.recover/tenant-full-owner checks, genuine authoritative
evidence retention/validation and policy attribution. GPT must integrate that
reviewed producer with final mutation and private-result fencing after blocking
work, mandatory real central appendOnce/chain receipt and effect-aware Flow.
Current policy reauthorization on retry must not rewrite first-decision metadata.
Raw trusted persistence remains inaccessible to an unauthenticated operator path.

All central delivery/current-result checks remain mandatory, including a MANUAL
tail on an otherwise cached CONFIRMED effect. V80 stays outside Flyway until the
ordered prerequisites/exact peer/final PR gates pass. No legacy backfill, public
endpoint, CDI/adapter/Flow activation or package/acceptance promotion.

## Verification and lifecycle

See docs/handoffs/gpt/WP-108-manual-decisions.md for exact commands, source SHA,
execution evidence and limitations. Tests must cover independent Java/SQL
canonical identity, concurrent/current-history conflicts, actual before/after
commit-reply loss, atomic rollback/source omission, retention, caller JTA isolation,
interruption, cached pending-audit refusal and separate compensation completion.
Data is tenant-restricted operational/audit attribution; evidence content remains
in an approved scoped custodian, not in this metadata. FK/immutable retention has
no invented expiry or purge. Backup/restore/retention/partition and real operator
process/failover/cross-owner policy/central-consumer evidence remain open.
