# WP-103 approved allocation store (GPT -> Claude)

Status: partial preparation; no new PR, CDI bean, migration or provider activation.
#55 stays the sole GPT implementation PR at ea56f33, awaiting exact Claude review.
This extends the task-authority/budget decision after your #56 model-call finding.
It does not transfer approval from any earlier shared interface.

## Producer and consumer

The trusted complete-occurrence/approved-profile producer resolves the FIRST
canonical ResourceCallScope through ResourceInvocations before allocation. First
load an existing allocation; retries reuse its actual original contract id/grant/
profile/deadline after validating live approved compatibility. Do not mint another
child task ULID on each credential refresh, substitute the new SDK retry counter,
or widen fields from HTTP input. A changed approval conflicts; no new budget.

Shared APIs: AgentTaskContractFingerprint, ApprovedModelProfile,
ResourceTaskAllocation, AllocatedResourceTask and ResourceTaskAllocations.
The allocation key ra1 hashes CanonicalEncoder domain
 oa.resource.task-allocation.v1 + text(original scope.key).
Its content hashes domain oa.resource.task-allocation-contract.v1, text(call key),
text(full scope fingerprint), optionalText(actual parent id), text(full contract
fingerprint), text(model-profile fingerprint). Full contract fingerprint domain
 oa.agent.task-contract.v1 binds id/goal/count+sorted tools/count+sorted data
scopes/risk/iterations/wall seconds+nanoseconds/cost/full subject/grant. Profile
fingerprint domain oa.model.approved-profile.v1 binds id/version/config digest/
full registered agent/model/count/ceiling/time seconds+nanoseconds/output tokens.
No data digest, retained row or constructor proves approval/current permission.

Contract goal is at most 4000 Java characters: prefer an approved exact request
fingerprint reference, never truncate a 4096-character topic. The existing task
wire codec stores whole seconds; finer task duration precision refuses BEFORE
I/O, not rounding. Model timeout nanoseconds are preserved explicitly in JSON.
The profile needs a real approved provider/price/retry/config ceiling; no token
cost is guessed and LlmClient's string result is not precise billed usage.

JdbcResourceTaskAllocations is explicit opt-in in GPT lifecycle (no CDI).
Proposed V78 stays in db/proposed, following separate proposed V77. It validates
actual call/ro1/registry attribution and status/deadline, real compatible issuing
or narrowed model grant and ALL ancestors, chosen registered agent, and the
actual parent task's instance/full owner/legacy ws1/thread/contract narrowing.
Parent row and grants remain retained. New allocation precedes the one execution
entry via a validated FK; pre-existing unallocated V76 facts stop activation and
require a reviewed historical decision, never fake backfill/deletion. V78 must
not be moved into Flyway before the ordered shared reviews/adoption gate.

The database chooses original createdAt and deadline AFTER lock waits, bounded
by task lifetime, actual grant-chain expiry, remaining parent lifetime and registry
deadline. One short JDBC transaction suspends caller JTA. Allocation/readback
compares full scope, exact JSON shape, all fingerprints and timestamps; a missing
joined call/occurrence is inconsistency, not NONE. SQL/commit/JTA failure throws;
reconcile SAME allocation. Historical reads/exact repeats work after closure or
revocation and never extend deadline or return dispatch permission.

## Data review and lifecycle

| Domain | Authority and lineage | Classification | Lifecycle/retention |
|---|---|---|---|
| Allocation | First retained canonical call + actual approved child contract/profile + real parent/grant chain | Confidential owner/goal/profile metadata; no raw tokens/prompts | Immutable; no UPDATE/DELETE/TRUNCATE, no generic TTL cleanup |
| Time | Database clock after serialization, actual ancestor/parent/registry bounds | Internal control data | Original lifetime retained on retries/reopen |
| Model budget | NOT implemented here; future durable per-node reservation/unknown-charge protocol | Confidential usage/authority context | Must use this original immutable allocation; never caller integer/JVM reset |

Storage is the existing PostgreSQL transaction store; no cache is authority.
Quality thresholds: one exact allocation per canonical call, no changed content,
full FK retention, coherent reads, exact codecs, no lost-commit duplicate/reset.
All SQL proposal tests own committed UUID schemas cloned from real metadata;
only those exact generated schemas are removed. Actual identity fixtures use
existing services, no production identity/policy/agent source changes. Parent
fixture tasks and registry rows are closed through their normal owned APIs.

Restore/retention gates remain: preserve call, occurrence, allocation, parent,
grant and execution together; missing/changed/corrupt metadata refuses. No
partition/backup/archival/model-consumer recovery proof is claimed by these tests.

## Verification record

Author verification=GPT; exact tested SHA and Linux results recorded after CI.
Local all206 contracts (seven new), three new preflight/codec cases pass zero
failures/errors/skips. Application reactor test compilation passes; skipped-test
compilation is NOT SQL execution. Fifteen actual SQL/store cases compile for
new Linux execution: original repeats/reopen/credential refresh, SDK counter
canonicalization, exact/changed concurrent allocations, actual commit reply loss,
caller JTA rollback, changed contract/profile/parent/request, parent narrowing/
remaining lifetime, real unrelated/revoked/mismatched-agent grants, historical
readback, DB time after lock wait, outage/corrupt metadata/exact JSON, retention/
execution FK, and activation refusal for unallocated historical execution facts.
CURRENT_PRODUCT_SQL_CI_PENDING

## Explicit consumer requests and open gates

Claude: consume only after these specific contracts/schema/backend are reviewed
and integrated. Supply real approved profile/config/grant narrowing and model
call ceiling requirements; actual live policy/model gateway and private async
context remain yours. Refresh identity/grant/scope plus registry/parent/task/
profile/current execution AFTER blocking I/O before every provider call/output.
Do not accept these records or a successful find/allocate as a provider permit.

Remaining GPT shared work: durable per-node atomic budget/call reservations and
unknown-charge retention tied to this allocation, plus composite execution gate.
Remaining Claude work: governed model gateway/non-bypass rule, no-call denial/
error/audit/outage tests and actual usage/retry reconciliation requirements.
Full occurrence producer, inspector material findings, actual HTTP/graph/model/
process/owner/partition/restore/audit and acceptance gates remain. Direct-call
production model entry still refuses absent a real allocation. WP-103/WP-202/
WP-108 stay partial; no new Acceptance annotation or acceptance-status promotion.

#53 d38776c and #56 26def97 remain unchanged; #56 REQUEST_CHANGES6288665045 still
applies, including required consolidation to one Claude implementation PR.
Separate WP-108 producer0a89ee5 and conformance2f31ba8 evidence is retained by normal
merges/handoffs and does not become current allocation SQL/consumer proof.

First CI c889a00 / BOTH39425642182 FAILED: both full logs show15 allocation
cases, four parent-backed errors (operator does not exist: boolean -> unknown),
11 other cases passing, contracts206/runtime344/testkit196 pass, app319 with4
errors. No real-provider/process success is claimed for that run. Parent JSON
operands of <@ now have explicit parentheses, per PostgreSQL16
[operator precedence](https://www.postgresql.org/docs/16/sql-syntax-lexical.html#SQL-PRECEDENCE).
Negative SQL cases now require exact SQLSTATE23514/23503/08006, so a parser/type
error cannot masquerade as successful denial. Added real missing-table42P01 and
future parent-created-time refusal inside the existing15 cases. Future parent
time is refused rather than granting guessed remaining lifetime. Workflow retains
all affected suite XML and actual conformance outputs for independent inspection.
Fresh corrected-product CI remains required; first failed evidence is preserved.
