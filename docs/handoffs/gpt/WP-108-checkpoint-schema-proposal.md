# WP-108 checkpoint profile adoption: executable proposal

Reviewer=GPT (author verification, not peer approval).
First product commit:651e12a1e054426ee7fc4e3dc0c1f8fe12f674da (nine methods/20 cases).
Current refinement adds inheritance refusal; exact final product is recorded below after execution.
Verdict: app/test compilation and JVM augmentation PASS; actual proposed SQL,
real saver compatibility, failure cases and strict Linux execution PENDING.
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

ResearchCheckpointSchemaTest now has ten compiled methods/22 required observations:

| Verification | Required result | Current evidence |
| --- | --- | --- |
| Fresh/idempotent and compatible nonempty adoption | Same constraint identities, original row ids/private text unchanged | Compiled; Linux execution pending |
| Partial, wrong type/nullability/default/PK/FK/extra column |23514, no completion/reset/repair, identical original rows/keys | Compiled; pending |
| Missing index/wrong predicate/order/extra uniqueness |23514 with unchanged history | Compiled; pending |
| Namespace shadow/view/RLS/check/user trigger |23514 before adoption, no guard removal or shadow store | Compiled; pending |
| Inherited parent/descendant |23514, including measured descendant rows in ordinary saver queries | Compiled; pending |
| Actual pinned saver createTables(false) | Real INSERT/read/UPDATE/release through committed physical connections still work | Compiled; pending |
| Concurrent table writer |55P03 NOWAIT, unchanged history, no mutation retry | Compiled; pending |

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
ALL22 unique expected observations. Passing source compilation, generated tests,
an incomplete report, skipped tests or another product's green CI is not execution
evidence. Local package -DskipTests compiles the new source and augments the app;
it does not run PostgreSQL or native Cedar. Required actual results remain open.

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
compilation/JVM augmentation PASS; actual SQL remains pending. Original
BOTH39525585412 retains its own651e12a SHA/results and cannot prove this refinement.
The new exact product requires fresh BOTH evidence. No applied migration, runtime
call, role or Claude source is changed; main513b878/c9ce389 remain frozen.
