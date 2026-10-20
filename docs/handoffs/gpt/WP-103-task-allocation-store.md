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
actual call/ro1/registry attribution and status/deadline, real issuing/narrowed grant
ancestry and ALL ancestors, chosen delegate matching the profile agent, and the
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

Author verification=GPT; exact tested product SHA
1ae6886667b3d721aff05a5ee39be8bedd0c2532. Verdict: preparation verified;
integration HOLD for exact peer/final PR/consumer gates.
Local all206 contracts (seven new), three new preflight/codec cases pass zero
failures/errors/skips. Application reactor test compilation passes; skipped-test
compilation is NOT SQL execution. Fifteen actual SQL/store cases now execute successfully in BOTH Linux lanes: original repeats/reopen/credential refresh, SDK counter
canonicalization, exact/changed concurrent allocations, actual commit reply loss,
caller JTA rollback, changed contract/profile/parent/request, parent narrowing/
remaining lifetime, real unrelated/revoked/mismatched-agent grants, historical
readback, DB time after lock wait, outage/corrupt metadata/exact JSON, retention/
execution FK, and activation refusal for unallocated historical execution facts.
At that EXACT product, [BOTH39426839423](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39426839423)
is SUCCESS. Finished standard118641425853/delegation118641426453 full logs inspected:
contracts 206 / runtime 344 / testkit 196 / application unit 319 in EACH lane,
including allocation SQL 15 / allocation contracts 7 / preflight-codec 3 /
conformance 7 / existing binding SQL 17 / proposed execution SQL 6 / proposed
central source SQL 6. Real standard 3 / delegation 8 / core process recovery 1
EACH and both strict checks pass; failsafe totals 4 and 9, zero failures/errors/skips.
No Maven error or cause lines in either finished log. This is the application
reactor, not the final all-project implementation PR review/build gate.

Actual artifacts 12267550099 (standard) and 12266976404 (delegation) downloaded.
Their verified GitHub digests are e784de782d5a42b8c1ff1862b825d347e8c93049f51e611870d7ed7ac7e25cee
and c9734f7c34177a3755fac366c89dcc3cd88e9dc8aef2e6ac43b829109be0eecc.
All 152 actual suite XML reports per lane parsed: positive counts and zero
failures/errors/skips. Exact 15 current allocation method names matched, no
missing/duplicate cases. require_keycloak_evidence.py rerun with the matching
--profile and --effect-recovery against both downloaded roots. Actual reports
independently match the conformance baseline except ONLY its explicitly bounded
wrong fork-order fingerprints; 48 execution outputs / 74 exports / two expected
writer diagnostics retained in each. Green reproduction remains NOT full OWS
semantic conformance. Separate WP-108 audit-process 4 is not included/transferred.

Commands: mvn -B -pl modules/oa-contracts test (all 206 local); selected application
reactor test with -Dtest=ResourceTaskAllocationTest,ResourceTaskAllocationPreflightTest
and -Dsurefire.failIfNoSpecifiedTests=false (7+3 real local, all app tests compile);
provider workflow runs mvn -B -pl apps/app-runtime -am verify with required actual
provider/core ITs; Python tooling 76 / render_requirements.py --check / diff checks.
The connection-outage branch uses a JDBC failure double; missing-table42P01,
lock-wait expiry and malformed joined metadata are actual PostgreSQL tests.
No actual network partition, restored backup, consumer model call or JWT/policy
proof is inferred from these allocation fixtures.

## Explicit consumer requests and open gates

Claude: consume only after these specific contracts/schema/backend are reviewed
and integrated. Supply real approved profile/config/grant narrowing and model
call ceiling requirements; actual live policy/model gateway and private async
context remain yours. Refresh identity/grant/scope plus registry/parent/task/
profile/current execution/original allocation deadline and durable budget AFTER
blocking I/O before every provider call/output. SQL checks retained ancestry and
delegate consistency; it does NOT prove model-action scope, actual registry
approval or the provider cost guarantee. Those consumer checks remain required.
Do not accept these records or a successful find/allocate as a provider permit.

Remaining GPT shared work: durable per-node atomic budget/call reservations and
unknown-charge retention tied to this allocation, plus composite execution gate.
Remaining Claude work: governed model gateway/non-bypass rule, no-call denial/
error/audit/outage tests and actual usage/retry reconciliation requirements.
Full occurrence producer, inspector material findings, actual HTTP/graph/model/
process/owner/partition/restore/audit and acceptance gates remain. Direct-call
production model entry MUST refuse absent a real allocation; that consumer
implementation/no-call proof remains open. WP-103/WP-202/
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
Corrected product 1ae6886 passes BOTH39426839423 as recorded above; the first
failed run remains preserved and is not used as passing evidence.

Final local build verification: mvn -B verify -DskipTests BUILD SUCCESS for all
eight reactor modules (compile/package only, not a full local test suite). Actual
python tools/check_dependency_policy.py then holds using freshly generated
dependency lists. No dependency/version/production-scope upgrade is introduced.
