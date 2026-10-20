# WP-108 governed effect and manual-decision authority handoff

Producer/contracts/integration: GPT. Identity/PEP/audit consumer: Claude.
Date: 2026-10-20. Package remains partial; no public effect/operator path enabled.

## Concrete scope contract

EffectScope(tenant, registry instance ULID, full owner Subject) is framework-free
data resolved from the authoritative registry. Its threadId/command methods use
the EXISTING ws1/ef1 derivation, preserving T03/recovery keys. requireKey rejects
keys from a different tenant, owner id or registry instance. It is not a bearer,
authority lease or proof that any supplied tuple is authentic. The caller MUST
load the registry by verified tenant/instance and compare full owner kind/id.

Inspection corrected the initial mapping concern: ThreadId already hashes the
length-prefixed tenant/user/registry-instance tuple. Re-derive it from the trusted
row; do not add a speculative thread-to-tenant migration or reverse a hash.
Historical ws1 omits subject KIND, so checking only the hash is insufficient;
compare the full immutable registry owner. No existing key/schema is rewritten.
No migration beyond V69 is allocated by this handoff.

## Cross-owner request to Claude

Please supply the following small identity/policy/audit integration in your
reserved paths, as WP-103/WP-102 interface handoffs rather than a second owner
of WP-108. GPT implements the effect/lifecycle/adapters/shared configuration and
tests after the owned interfaces are reviewed and merged.

1. A current authority recheck for a governed effect on this resolved scope and
   registered adapter target: verified effective caller/tenant, full owner or
   explicitly authorized delegation, exact live issuing grant/all ancestors,
   target/audience/scope and expiry. Sample signed expiry/current grant state
   AFTER blocking policy/audit/storage work immediately before returning dispatch
   authority. Never accept DEV_HEADERS/UNVERIFIED on a production effect/manual
   path. CallerContext currently stores no signed expiry; a one-time provenance
   flag alone cannot prove freshness after a slow call. Publish the minimal
   transient revalidation seam; never put a bearer into a command/checkpoint.
2. An explicit fail-closed OPERATION policy for a manual effect decision with
   verified oa.recover and instance/tenant ownership checks. Proposed resource
   operation:resolve-effect; do not assume existing resolve-signal recovery
   permission covers it. Ordinary execution and compensation keep their own
   scoped registered-target policy. Register any new Cedar path through GPT's
   shared ownership/manifest update before creating the file.
3. A central-audit consumer contract for intent/dispatch/unknown/outcome/manual/
   compensation transitions. Include immutable scope, logical key fingerprint,
   transition identity/sequence, decision/policy attribution and safe reason.
   Exclude payloads, credentials, target reply bodies and unbounded error text.
   Decide dedup identity and behavior for a lost audit commit reply. Local ledger
   journal rows are not the central hash-chain audit. GPT owns additive SQL and
   atomic journal/outbox producers; Claude owns the audit projection/PEP leaves.

Return exact affected paths, ephemeral authority/freshness API, policy requests,
audit delivery shape and denial/outage tests. Do not expose a manual endpoint or
reclassify an unknown effect as failed merely because it timed out.

## GPT integration obligations and acceptance gates

Before any target dispatch, bind the command to the resolved scope, obtain
current policy/identity authority and durably commit intent/attempt; mandatory
audit must complete before permission returns. Registry lifecycle/owner loss
and effect claims must coordinate, not trust task metadata. Preserve finite
network timeouts, registered adapters, stable node/iteration/foreach occurrence
keys and the current target retention/lookup-before-replay contract. SDK callback
return is not success; INTENDED holds progress. Prove actual Flow recovery and
owner-loss/compensation windows, not just a port double or this scope value.

Manual decisions require cross-process effect exclusion and concurrency control,
current policy/identity after all blocking work, mandatory audit, authoritative
evidence and retained decision attribution. Only resolve an actual unresolved
intent; never turn a competing confirmed/compensated outcome into a new decision.
No arbitrary manual COMPENSATED without the separately confirmed compensation
contract. A lost commit reply must be read back by stable decision identity, not
retried as a fresh decision. Keep raw ledger methods internal/trusted.

Required denials/failures: forged tuple/key; wrong tenant/full owner/delegation;
expired or revoked authority during slow audit/storage; unregistered target;
policy/audit/storage outage (zero new target calls); simultaneous recovery and
manual decisions; duplicate/lost decision replies; unknown target outcome;
failed compensation; process/partition/Flow restart with the SAME operation key.
Central audit/tracing and complete adapter/manual/lifecycle proof remain open.

The branch is preparation, with no additional active implementation PR while
#46 is under review. WP-108 does not wait for whole WP-102 completion; it waits
only for the particular verified-caller/authority/policy/audit producer APIs
needed by each exposed consumer. Existing internal ledger/T03 evidence remains
valid for its scoped capability; this contract does not close M06/M07 or WP-108.

Local validation: mvn -B -pl modules/oa-contracts test
-Dtest=EffectScopeTest,EffectCommandTest -> BUILD SUCCESS, scope4/existing-key2,
zero failures/errors/skips. Tests preserve existing recovery keys, reject another
tenant/user/registry-instance key, retain exact Unicode, and explicitly require
full Subject checking despite the historical kind-free hash. The value type has
no network/store dependency or authentication claim. No policy/provider/manual
execution test is represented by these contract cases.
