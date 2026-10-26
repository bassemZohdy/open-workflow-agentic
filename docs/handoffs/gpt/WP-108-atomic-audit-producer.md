# WP-108 atomic audit producer

GPT-owned runtime increment after merged V77/PR85. Explicit opt-in DATA producer, no relay, central append, mandatory
receipt adapter, production admission, graph, model allowance or V78..V80 activation. WP108 remains partial.

## Boundary

The one-argument injected JdbcEffectLedger constructor is unchanged in behavior and has no projection. The explicit
three-argument constructor takes a transaction manager and EffectAuditProducer; prepareScoped accepts full EffectScope
and a stable EffectCommand, checks registry attribution under its real row lock, and retains newly established intent,
journal, scope, original source/draft and trigger-enqueued pending delivery together. Only a known inserted==1 may bind
new provenance; an existing unproven legacy intent refuses instead of being upgraded from the owner-kind-free ws1 hash.
Exact retries verify and return the original retained fact/draft, including after a real commit followed by a lost reply.

Producer-routed later scoped journal mutations serialize on the existing ledger row before allocating their journal id and capture
the corresponding source in the same transaction. Compensation inherits retained provenance after registry completion;
it does not inherit current permission. A writer without the projection cannot commit a later scoped journal event.
The default/CDI instance explicitly refuses scoped preparation before storage access. Unscoped legacy behavior remains.

The producer reuses the existing owned transaction helper and explicitly suspends/resumes an ambient caller transaction
for the opt-in instance. Caller rollback cannot erase its data. A commit/cleanup/resume error may follow committed data;
never infer absence or target nonapplication from that error. There is no fresh callback/capability here and no target
call. Only same-key retained-data reconciliation is possible. Operational pool/network bounds and failure cleanup remain
part of the reviewed deployment/guarded-adapter adoption; this does not claim the JDBC gate's bounded callback protocol.

Retained reads recompute original scope/key/source/draft equality. An actual absent source returns empty, while an
existing source with broken journal/ledger anchors refuses (LEFT JOIN plus explicit anchor check). Bounded per-key pages
are DATA pages, not a complete-history or central-receipt assertion. The future relay must verify full journal/source/
queue completeness and predecessors BEFORE append. Registry checks here are structural; current caller/grant/PDP/task/
lease/target, mandatory central receipt, exact resource-call mapping and actual Flow/node/output/recovery stay gates.
Legacy read-only target lookup/replay is not fenced by a source constraint; adopting the producer alone cannot approve it.
No public endpoint, new role grants or positive default is introduced.

## Evidence

Java21/PostgreSQL16.15, verified fresh common-suite DB plus the store class's own verified UUID database:
59 focused cases pass (producer units6/store13, existing ledger4/guard5/durable16/cancellation6/tracing3/telemetry5,
default admission refusal1), zero failures/errors/skips. The common DB is dropped without FORCE after Maven terminates.
The store fixture runs actual ordered migrations through V77; it no longer reapplies old proposed V75/V77 copies.

The first carried-forward store9 run had one owner TRUNCATE probe stop at the new delivery FK0A000; CASCADE corrects
the probe without relaxing42501. New private controls disable descendant guards, prove the intended parent refuses,
then disable only that parent too and prove the same probe succeeds; all data and trigger changes roll back.
Real queue INSERT failure proves all five producer facts roll back. Four concurrent exact preparations retain one source.
Real JTA caller rollback, actual lost commit reply, finite transitions/compensation after registry end, unconfigured CDI
and retained corruption are covered. No target or central-append receipt is claimed by this suite.

A new orphan-read regression on the original reader:13 store cases, one intended failure (expected refusal, got empty),
zero errors/skips. Corrected LEFT JOIN reader:59 focused cases pass. A final targeted control also verifies actual absence
is still empty. Logs under target/atomic-producer-*.log. Exact Claude review and required current Linux CI remain pending.

## Preparation provenance

Only the producer/ledger patch and corresponding tests from historical preparation2941204 are carried forward.
Later branch changes for V80/manual decisions, delivery, audited target adapters or other proposals are excluded.
Current review/CI are required; old preparation evidence is not transferred as approval.

## Peer follow-up

Initial Claude exact66571db PASS independently ran59 cases. All three notes are addressed: clarify structural checks
precede retries (historical data readback remains separate); preserve an original work exception and attach a concurrent
resume failure as suppressed; page through retained origin/event references so a known orphan is not silently skipped.
New real JTA controls cover original refusal plus lost resume reply, and actual commit plus lost resume reply retaining
one original source without acknowledged return. The orphan control now checks both direct and paged reads.
Final author61 cases pass (runtime17/app44, store15), zero failures/errors/skips, in a verified fresh suite database and
its declared private store DBs, dropped after exit. Log target/atomic-producer-final.log. New exact peer/current CI are
required; old66571db full1384 is retained only as earlier-head evidence, not approval of this final change.
