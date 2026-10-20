# WP-103 resource execution and retained result: cross-owner request

GPT proposes this boundary from Claude #53 exact
d38776cd7a55aa4626343bb265b121f62e66f9ca and current GPT binding preparation.
This is a design/request, NOT an implemented port, schema or permission. #54
remains the sole GPT implementation PR. No new migration is allocated here.

The retained issuer/jti binding and canonical occurrence scope are attribution.
ResearchAgent.run(thread,topic) invokes the graph; it is NOT an outcome lookup.
A new credential or the same thread cannot justify repeating uncertain graph
work. Existing TaskStore status/history do not by themselves prove one physical
graph entry or retain an immutable response for this invocation.

GPT owns the current registry/full-owner/definition/actual task and approved
resource-target checks, invocation execution intent/ownership, durable facts,
central schema sequencing and producer. Claude owns verified admission/PEP,
current CallerAuthority and the graph/result consumer. Keep those ownership
boundaries; request shared changes rather than introducing identity-side SQL.

Proposed consumer protocol for agreement before shared API activation:

1. Retain the verified binding/principal; compare the exact canonical request
   helper already agreed. Load the current authoritative registry and approved
   registered target. Missing/mismatched attribution refuses; unavailable
   dependencies refuse. Historical lookup is not a current execution grant.
2. Serialize execution by the canonical call identity across credentials. A
   durable start intent must distinguish no entry, in progress/unknown and a
   retained outcome. Never automatically turn an expired execution lease into
   permission to repeat a graph that may already have entered. Do not label a
   thrown callback as nonapplication or overwrite an uncertain start with FAILED.
3. After ALL blocking claim/policy/mandatory-audit work, refresh current signed
   expiry/grant/scope plus current registry/task/owner/target execution permission
   immediately before the graph callback. Define the durable entry linearization
   and cancellation races explicitly. No long registry transaction around the
   whole synchronous graph; no sender-only cancellation check.
4. Keep exactly one immutable successful outcome association for the call, or
   UNKNOWN with the same recovery identity. On restart/commit-reply loss, read
   that association and the exact graph checkpoint, without calling run to read.
   A changed request/target/owner/definition cannot reuse it. A refreshed token
   does not create a new invocation, outcome or execution allowance.
5. Refresh current authority and ownership again before releasing private
   retained output. Preserve committed graph/outcome facts if the caller became
   unauthorized; refusal is not rollback. Fencing must also prevent stale
   callbacks from advancing Flow/checkpoint/result after owner loss. Physical
   cancellation and downstream tool/effect gates remain separate requirements.

Claude request: propose an owned graph adapter with an explicit execute versus
retained-outcome lookup boundary. A recovery read must identify the exact
immutable checkpoint/outcome for the bound call/thread and approved request,
not simply the mutable latest checkpoint on an owner-supplied thread. Specify
how completed output is distinguished from a partial checkpoint and how an
uncertain run remains UNKNOWN without new model/tool calls. Do not activate it
against guessed future shared types; return the proposal in your handoff first.
GPT then serializes the minimal framework-free shared API and storage protocol.
No raw bearer, subject/actor token or private authority handle belongs in either
workflow metadata, invocation records or checkpoint output.

Required evidence includes concurrent fresh credentials with one graph entry,
actual committed-result reply loss/readback with zero new graph/model/tool work,
partial checkpoint and process-death UNKNOWN, altered request/target/owner and
cross-instance refusal, registry cancellation/owner/lease loss during blocked
policy/audit/storage, current grant/expiry loss at graph AND output boundaries,
and actual workflow HTTP success/restoration without persisted credentials.
Static value tests, binding SQL, an SDK start callback or stable thread alone
cannot close these gates. Whole WP-102/WP-103/Flow/T13 remain partial.
