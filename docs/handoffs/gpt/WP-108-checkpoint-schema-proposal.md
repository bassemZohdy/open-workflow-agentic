# WP-108 checkpoint profile adoption: executable proposal

Reviewer=GPT (author verification, not peer approval).
First product commit:651e12a1e054426ee7fc4e3dc0c1f8fe12f674da (nine methods/20 cases).
Current reviewed product: ae254531bb3f8e417cb6886389043298f43946a9.
Verdict: GPT author verification PASS for final exact proposed SQL/real saver/negative/strict execution.
Other-agent/current PR/migration/role/runtime adoption gates remain HOLD; no peer approval is claimed.
This is not an applied migration, runtime change or completed work package.

## Scope, source and ownership

Responds to Claude's merged #53 a71fb1f table-ownership request6304715187 and
the pinned-profile intake6305178422. Baseline is main513b878. GPT owns proposed
SQL, tests, configuration and eventual version allocation; Claude owns turning
off ResearchAgent.createTables(true) AFTER reviewed schema activation. Do not
change that runtime call or global baseline-on-migrate in this proposal.

`db/proposed/research_checkpoint_profile.sql` is outside Flyway. It is deliberately
UNVERSIONED: no new number is allocated while the existing reserved V71..V80/72.1
integration sequence is open. A later reviewed additive migration is required.
No applied migration or Claude source is modified. Main tracking and membership
candidatec9ce389 remain frozen for that separate exact peer review; no second
implementation PR is opened for this preparation.

| Data domain | Authority/source revision | Classification | Lifecycle/retention |
| --- | --- | --- | --- |
| Research checkpoint profile | Pinned postgres-saver1.9.5 v1.1 DDL/commands | Restricted private state; tests use synthetic owned data | Preserve original history/ids; actual saver updates/releases remain supported |
| Schema proposal | GPT-authored catalog validation and fresh-profile DDL | Internal proposal, not dispatch authority | No backfill/reset/deletion or activation |
| Test observations | Actual PostgreSQL/real saver, once executed | Synthetic fixture data; report contains diagnostic hashes, not private text | Most fixtures roll back; committed compatibility fixtures clean ONLY their owned UUID namespace |

The source JAR/schema/command hashes and complete pinned shape are in
WP-108-checkpoint-schema-intake.md on the existing fingerprint preparation.
Tests load the ACTUAL dependency resource db/migration/v1.1__init.sql and assert
SHA256f25ce808c4bf2dcca9bd23be68fcdbc374068721a0901477c886ba85e116552d;
no second invented vendor profile is substituted. V2 is a separate incompatible
profile. Proposed target is the current public profile, with explicit namespace/
unqualified-name checks so another schema cannot be silently shadowed.

## Proposed behavior and alternatives

Fresh target requires BOTH tables absent and creates the pinned profile. A
partial pair refuses. Legacy tables get no DDL repair or data rewrite: verify
regular permanent relations, target namespace, exact columns/types/nullability/
defaults, nongenerated identity, keys/retained cascade, required named btree
index structure/operators/order/collation/predicate and expected uniqueness.
Unsupported inheritance/rules/RLS/user triggers/check constraints refuse rather than being
removed. Additional nonunique indexes are not repaired or certified. This is
bounded compatibility validation, not hostile-administrator or disk/index
corruption immunity.

Existing tables require ACCESS EXCLUSIVE NOWAIT. A live writer causes55P03 and
startup must refuse; no indefinite wait, worker kill or mutation retry. Catalog
checks and fresh creation share one transaction. No checkpoint is selected,
decoded, deserialized or replayed by the SQL, and no private-state validity or
causal chain is inferred from schema compatibility.

Rejected alternatives: blind CREATE IF NOT EXISTS hides incompatible history;
automatic drop/recreate/baseline destroys provenance; guessed causal backfill or
append-only checkpoint constraints contradict the real pinned saver update API.
Migration ownership alone does not grant output/entry permission or complete
backup/restore/retention/authority protocols.

## Verification plan and fixture boundaries

ResearchCheckpointSchemaTest now has ten compiled methods/23 required observations:

| Verification | Required result | Current evidence |
| --- | --- | --- |
| Fresh/idempotent and compatible nonempty adoption | Same constraint identities, original row ids/private text unchanged | PASS: final exact Linux/BOTH |
| Partial, wrong type/nullability/default/PK/FK/extra column |23514, no completion/reset/repair, identical original rows/keys | PASS: final exact Linux/BOTH |
| Missing index/wrong predicate/order/NULLS NOT DISTINCT/extra uniqueness |23514 with unchanged history | PASS: final exact Linux/BOTH |
| Namespace shadow/view/RLS/check/user trigger |23514 before adoption, no guard removal or shadow store | PASS: final exact Linux/BOTH |
| Inherited parent/descendant |23514, including measured descendant rows in ordinary saver queries | PASS: final exact Linux/BOTH |
| Actual pinned saver createTables(false) | Real INSERT/read/UPDATE/release through committed physical connections still work | PASS: final exact Linux/BOTH |
| Concurrent table writer |55P03 NOWAIT, unchanged history, no mutation retry | PASS: final exact Linux/BOTH |

Most DDL/DML fixtures ALWAYS roll back their UUID namespace. Two deliberately
commit an OWNED fresh UUID namespace to exercise real saver transactions and a
second real connection holding a writer lock. Cleanup validates the generated
name and current-user ownership before dropping ONLY that namespace. No global
table reset, forced session termination, fake transaction manager or suppressed
commit is used. Real saver reads deserialize only our own generated synthetic
state; this is not permission for the inspector to deserialize unknown history.

Tests alter only the proposal's single expected-schema literal to their owned
namespace. The same SQL guard is executed, including all keys/index validation;
no production parent trigger is disabled or constraint weakened. Snapshot
equality is asserted over actual fixture rows before/after, while retained report
hashes are test diagnostics, not a second product output-fingerprint recipe.

The actual saver case also measures that release retains history and a later
entry creates another physical run. This is a known recovery hazard, not a
feature/permission claim. The current execution fence must still prevent that
re-entry for a governed invocation.

CI retains actual app/module XML plus target/conformance/checkpoint-schema/cases.json.
The report must name the exact product SHA, pinned/proposed source hashes and
ALL23 unique expected observations. Passing source compilation, generated tests,
an incomplete report, skipped tests or another product's green CI is not execution
evidence. Local package -DskipTests compiles the new source and augments the app;
it does not run PostgreSQL or native Cedar. Actual final Linux results are recorded
below; activation gates remain open.

## Activation, freshness and restore gates

Before activation: current exact peer review/required PR CI; compatible nonempty
and failure proof on PostgreSQL16; owned role/datasource/configuration plan; ordered
version allocation and migration-first app startup/class-order tests. Then Claude
changes the owned createTables setting and proves the real runtime still starts
and refuses incompatible storage before graph/provider work.

Source-of-truth is the retained same-database profile, not a derived export.
There is no replica/freshness SLA, volume/RPO/RTO measurement or restore drill
in this proposal. Backups must preserve tables, private bytes, original physical
ids and matching migration history; actual cross-state restore/retention/manual
and current registry/task/PDP/execution/output/central-audit gates remain open.
Contribution status is PROVISIONAL until actual SQL/compatibility evidence and
the later reviewed activation gates pass. No package/acceptance promotion.

## Inheritance refusal refinement

Code review found a missing catalog boundary in first proposal651e12a: ordinary
permanent tables can participate in inheritance while retaining identical visible
columns/keys/indexes. Saver SELECTs do not use ONLY, so descendants can change the
visible private history. The refinement rejects pg_inherits at EITHER endpoint
after the existing target locks. It does not detach a table or repair its rows.

One new method exercises two actual owned fixtures: a checkpoint descendant with
another retained row (ordinary parent SELECT observes two rows), and a checkpoint
inheriting a separate ancestor column. Both must refuse23514 with original row/
constraint snapshots unchanged. Most other cases remain unchanged. App/test
compilation/JVM augmentation passed at publication; actual SQL was then pending. Original
BOTH39525585412 retains its own651e12a SHA/results and cannot prove this refinement.
The new exact product requires fresh BOTH evidence. No applied migration, runtime
call, role or Claude source is changed; main513b878/c9ce389 remain frozen.

## Original exact execution and remaining refinement gate

Reviewer=GPT author verification PASS at original651e12a1e054426ee7fc4e3dc0c1f8fe12f674da.
[BOTH39525585412](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39525585412)
SUCCESS attempt1: standard118979328141/delegation118979327889. BOTH finished
complete logs and ALL148 actual XML suites EACH inspected,1060/1065 cases,
contracts183/runtime389/testkit196/app288 plus Failsafe4/9, consistent unique
cases and zero failures/errors/skips. Exact original checkpoint9/inspector24/
governed12/rule3/physical-provider1 method sets match that source. Real strict
standard3/delegation8/core1 current checkers rerun successfully on actual archives.

BOTH actual cases.json reports have the exact651e12a product SHA, pinned DDL/
proposal hashes and ALL20 original unique observations. All negative SQLSTATEs
match23514 or writer55P03; exact before/after retained-row diagnostic hashes
match. Actual saver createTables(false)/changed checkpoint id/release retained
history/re-entry additional physical run assertions pass. The latter is measured
hazard evidence, not authority to re-enter governed work.

| Profile | Artifact | Bytes | SHA256 |
| --- | --- | --- | --- |
| standard |12313582179|1869684|1c5a85028a69011a3b5db8c34f973ea1b26f0e87931076681aa9f2fb914fe100|
| delegation |12312696965|1868497|4846abef8b8357a8b21bf0ff244e46e700516f366debda5573055ad3eb1cf791|

GitHub metadata/digests/CRC/bounded safe extraction verified. This proof remains
under its ACTUAL original SHA: it does not prove the later two inheritance cases
or the later null-uniqueness refusal. The superseded inheritance-only263e53a/
BOTH39526209425 was cancelled while QUEUED before tests; both job conclusions
are cancelled and no execution success is claimed.

A second catalog review found that required index indnullsnotdistinct must be
false to preserve pinned nullable-name uniqueness. The final refinement adds
that guard plus one negative case, giving ten compiled methods/23 observations.
Local app/test compilation/JVM augmentation and tooling76/generated checks pass.
At that refinement publication, final exact BOTH remained required; the finished
verification is below. Peer/activation gates still remain. No version/role/applied
migration/runtime/Claude source change or package promotion.

## Final exact refined execution

Reviewer=GPT; reviewed product **ae254531bb3f8e417cb6886389043298f43946a9**; author verification
verdict PASS for this bounded unversioned proposal. Other-agent/current required
PR/activation gates remain HOLD. No independent peer approval is implied.
[BOTH39526547517](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39526547517)
SUCCESS attempt1/no rerun, standard118982436133/delegation118982436382.
Both finished full logs and ALL148 actual XML suites EACH independently inspected:
1061/1066 cases, contracts183/runtime389/testkit196/app289 plus Failsafe4/9,
consistent nonzero unique counters and zero failures/errors/skips.

Exact current source-method sets match checkpoint10/inspector24/governed12/rule3/
physical-provider1/real standard3/delegation8/core process1. Both strict current
provider/effect-recovery checkers rerun on the actual archives. Both actual
cases.json name this EXACT product, the pinned DDL and current proposal hash,
and ALL23 unique observations. Expected23514 refusals (including both inheritance
endpoints and NULLS NOT DISTINCT), writer55P03 and matching before/after row hashes
pass. Actual saver createTables(false), original/replacement state/id reads,
release flag with retained physical thread and re-entry second physical run pass.
The latter remains measured hazard evidence, never permission to re-enter.

| Profile | Artifact | Bytes | SHA256 |
| --- | --- | --- | --- |
| standard |12313278655|1869104|15ea5c7891153ab9280fd0bb10de5436555d7e1f684e895424054a1ec81c5757|
| delegation |12313518379|1873248|8676cc4fe4bd9bb8f10cee4a9de5d4cccbceee84baba91115e464c1a04b20988|

Actual archive bytes/digests match GitHub metadata, CRC/bounded extraction/safe
paths verified. Original651e12a/20-case success remains historical, and cancelled
queued263e53a has no success evidence. No tested source/configuration follows
this final product in the documentation record.

Commands/results:

```text
mvn -B -pl apps/app-runtime -am package -DskipTests
  PASS: all reactor compilation, test compilation and JVM augmentation (local;
  no local PostgreSQL/native execution claimed).
python -m unittest discover -s tools -p 'test_*.py'
  PASS:76 tooling controls.
python tools/render_requirements.py --check
  PASS: tracking regenerated from source JSON; no status/acceptance promotion.
git diff --check
  PASS.
# Actual Linux CI in EACH profile, with the pinned real Keycloak server:
mvn -B -pl apps/app-runtime -am verify
  -Dit.test=KeycloakStandardIT,EffectCrashRecoveryIT
  -Dfailsafe.failIfNoSpecifiedTests=false -Dkeycloak.required=true
  -Dkeycloak.profile=standard -Dkeycloak.url=http://127.0.0.1:18080
# Delegation substitutes KeycloakDelegationIT/profile=delegation. BOTH PASS.
python tools/require_keycloak_evidence.py --profile <profile>
  --root <actual extracted artifact root> --effect-recovery
  BOTH PASS; actual XML exact method sets and all23 report/source-hash controls
  additionally inspected independently, not inferred from green workflow status.
```

After prioritized c9ce389 membership review and the tiny shared fingerprint API,
request exact final documented-branch peer review of this proposal/actual tests.
A preliminary branch PASS does not bypass later current-main reconciliation,
the sole implementation PR/current required checks or ordered version allocation.
Activation still requires a reviewed migration/role/datasource/startup contract
and Claude's owned createTables(false) change AFTER migration integration, with
fresh-start/incompatible-storage-before-provider tests. No applied migration,
version allocation, role grant, runtime switch, private output/current authority,
retention/restore/T03/full WP108 or acceptance promotion is made here.
