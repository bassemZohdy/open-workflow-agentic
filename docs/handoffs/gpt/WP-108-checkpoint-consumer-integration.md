# WP-108: checkpoint consumer and packaged startup proof integrated

PR71 exact6e02b0ebbda36733cd41c6b5442997048e5dcbf3 merged as694dc326 after
GPT review6406283085, full40253621994 and real-Keycloak40253622052.
Postmerge core40254990550 and provider40254990868 also pass. The agent
uses createTables(false), checks the actual public profile at startup/build/run,
and reuses that check inside the inspector's existing read transaction. Invalid
configured namespaces refuse before connecting; catalog functions/types are
qualified. The original ignored-config and function-shadowing findings are fixed
with negative controls, including an old-code control that accepted the shadow.

GPT independently ran68 focused cases on Java21.0.12.1/PostgreSQL16.15, all pass.
Actual core artifact12621662072 contains172 XML suites/1220 executed cases with
failures/errors/skips0. The exact three new configuration/shadow controls and the
production-default admission-refusal method ran. Real-provider artifacts contain
standard3/delegation8 plus effect-recovery1 each, all non-skipped. Digests, CRC,
24 profile observations, unchanged migration bytes and the actual checkout's
tree equality with the reviewed head were checked. See
[evidence](evidence/WP-108-checkpoint-consumer/current-ci.json).

PR72 exact031beee34d370a9f8a58c6bd61152e091f164faf merged as97343477 after
Claude source PASS6406298991 and full40255084555; postmerge40256568737 passes. Only GPT's138-line packaged
startup test was added. Actual artifact12622700785 contains173 XML suites/1224
executed cases, failures/errors/skips0; all four exact methods ran and the actual
tested tree matches the reviewed head. It proves fresh migration-first boot,
current-history drift refusal while retaining private rows/history, missing-table
refusal without creation, and unsupported-namespace refusal without fallback.
All cases assert zero model calls. The original-runtime control had1 fresh pass
and3 intended readiness-refusal failures; the exact revised source then passed4
on a fresh isolated LinuxJava21/PostgreSQL16 server. See
[startup evidence](evidence/WP-108-checkpoint-startup/current-ci.json).

The earlier local full attempt remains recorded as failed: a reused shared test
database had121 old STARTING rows ahead of a100-row scan before tenant filtering,
and an existing pinned-engine fixed ID. It stopped before the new process tests.
No fixture or assertion was weakened. The isolated targeted run and clean required
CI provide the positive evidence; that earlier attempt is not relabelled.

This validates the pinned schema and its read-only runtime consumer. It does not
supply dedicated deployed credentials, certify every pooled connection or prevent
later administrator DDL. The check holds ACCESS SHARE only for its transaction,
allows ordinary writers and immediately refuses conflicting exclusive DDL.
Profile compatibility grants no execution or retry authority. The legacy admission
port and missing approved-model context remain denying by default.

Next is the reserved V76 entry/backend and composite live fence. A public
Principal/scope/entry record is historical data, not an opaque admission witness.
No transient authority belongs in graph state, RunnableConfig/checkpoint metadata
or ambient ThreadLocal. Require a server-trusted acting-workload binding and fresh
identity/owner/lease/target/policy checks after blocking work, before entry, each
node and output. Missing mapping refuses. One durable fresh acknowledgement alone
may invoke the callback; new credentials/epochs, empty history and UNKNOWN cannot
permit another graph run. V77..V80 and full Flow/recovery acceptance remain later
gates. Original recovery failure40246336207 is still open despite20 non-reproducing
repeats; WP-108 and acceptance remain partial.
