# Changelog

- Merge WP-108 managed JTA pool PR109 as 57389b3 after Claude PASS at 53c89ab, CI40794331994 and both provider checks40794331895; post-merge build40796036689 and provider checks40796036659 passed, and Claude independently reran all 15 focused PostgreSQL tests after merge. The test-created pool demonstrates ambient caller rollback with separately durable decision, audit binding and outcome. Deployed datasource and grants remain activation gates; WP-108 and T03 stay partial.

- Merge WP-108 manual audit lost-reply retry PR107 as 3576a94 after Claude PASS and independent 14 focused PostgreSQL tests at 7567c9e plus PR CI40791984770 and post-merge main CI40793439594. A real committed audit whose reply and readback fail is retried with the same request under ambient JTA, leaving one applied outcome. WP-108 and T03 remain partial.

- Merge WP-108 manual audit suspension PR105 as 2305bce after Claude PASS and independent 13 focused PostgreSQL tests at a1e0737, plus PR CI40790001025 and post-merge main CI40791140439. The caller transaction is suspended around audit append and readback; managed audit datasource behavior and role grants remain activation gates. WP-108 and T03 remain partial.

- Merge WP-108 ambient transaction regression PR104 as 2c25b4b after Claude review and independent 12-test PostgreSQL run at 3d62ad4, plus PR CI40788462057 and post-merge main CI40789673082. Resolver and ledger connection suspension and retain-failure restoration are observed; managed audit datasource behavior remains an activation gate. WP-108 and T03 remain partial.

- Merge WP-108 tenant/isolation hardening PR102 as70a6337 after Claude exact-head PASS50d75c9, 12 focused PostgreSQL tests, exact-head CI40438255834 and post-merge main CI40439483024 SUCCESS. The retry-isolation test starts from REPEATABLE READ; tenant mismatch refuses before PEP. V80 stays unapplied, audit-table grants remain an activation gate, and WP-108/T03 governed Flow acceptance remain partial.

- Merge opt-in manual effect-resolution PR100 as601e1b1 after Claude exact-head PASS19681a0, 10 focused PostgreSQL tests, full exact-head CI40433470521 and post-merge main CI40435285080 SUCCESS. Keep V80 as an unapplied proposal with no CDI/public-route/Flyway activation; WP-108 and T03 governed Flow acceptance remain partial. Record tenant-before-PEP, runtime READ COMMITTED and future audit-grant checks as activation follow-ups.

- Merge model-reservation DATA increment PR98 as084a02d after Claude exact-head df8daa129c5d3a526a418238dbe4bf22afef3828 PASS and current full PR CI40428062558 SUCCESS. The 64 focused local cases and independent peer cases pass; post-merge main run40429186511 is tracked separately. Reservations remain opt-in and unapproved: no model call, source/role activation, V78/V79 deployment, package closure or acceptance promotion.

- Merge audit response freshness93 (ae57e4e) after exact GPT8a5896e PASS/independent37 and inspected full1472, including all3 native default-denial cases. Recheck signed identity after result materialization; expired reads release neither event data nor not-found evidence. Close the scoped audit caller/PEP response task while keeping tenant/emergency/other boundaries open. Combined91/92 main1463/provider1460/1455 evidence is verified.

- Merge observed audit history91 (3610fca) and private bounded recovery snapshot92 (5f5a229) after exact reciprocal reviews, independent73/11 and inspected full1452/1443 plus provider1435/1440. Receipts remain observed snapshot DATA; later writes, target permission and whole-call bounds are not inferred. Timeout/status/chunked-body probes exposed real diagnostic defects before correction; issue75 cause/fix and production profile76 remain open.

- Merge recovery diagnostics90 as99631cc after Claude exact5503932 PASS/independent5 and inspected full1432. Observe asynchronous dev-publish failures and expose disabled-by-default engine snapshots without inferring completion from absence. Actual postmerge allocation contracts main0856d92 passes1427; issue75 remains unresolved, with bounded timeout consumer follow-up in review.

- Merge allocation DATA contracts89 as4b74700 after Claude exact41ba16c PASS/independent209 and verified full1427 current CI (including combined delivery/model controls). No store, migration, approved source or reservation is activated; original-call identity and immutable full contract/profile/lifetime remain data. Issue75 diagnostic intake leaves its cause unresolved.

- Merge audit delivery87 (2570bea) and controlled model calls88 (2284d10) after exact peer reviews, independent53/63 and inspected current CI1395/1409, zero failures/errors/skips. Combined-main verification remains pending. Preserve default refusal and data/authority separation; record the staged allocation migration plan without activating V78 or inventing historical allocations.

- Merge explicit atomic audit producer86 (4f41a60) after Claude exactdfb8030 PASS/independent61 and inspected full1386; actual postmerge1386 also passes. Preserve original scoped sources, refuse legacy upgrades/orphan reads, retain resume diagnostics and leave default registration absent. Audit delivery87 (52 focused green plus intended pre-append negative control/restored1) and controlled model API remain pending final review/CI. No production target/graph/context or acceptance closure.

- Merge audit fixture84 (c91831d) and additive V77 facts85 (e5890ad) after exact reciprocal reviews, independent34/88 and inspected full1338/1365. Postmergee5890ad passes1365 with verified raw evidence. Original history is not backfilled; new catalog/type lookup controls and parent guard controls pass. No producer/relay/authority/graph activation or package closure. Atomic producer86 is independently prepared with59 focused green and pending peer/CI.

- Merge grant-expiry81 (3ba7f77), governed Running/JDBC bounds82 (40d8d7c) and zero-model-budget83 (8727e71) after exact reciprocal reviews and verified current CI. Combined main8727e71 passes1331 cases, including enabled native model-policy checks. Retain source/receipt data and callback uncertainty; production root, approved reservations, actual Flow/async/recovery and acceptance remain partial.

- Canonical DATA lookup80 merged891442d after exact Claude review and full1297; combined main1304 verified. Audit child fixture84 is independently reviewed with34 passing; V77 facts candidate has41 private schema checks and pending Claude review. Neither is merged yet.

- Merge exact credential-pairing PR79 as34e2fbb after GPT PASSf06ebee, independent49 and inspected full1298 plus real-provider CI. A derived rb1 fingerprint remains historical data; production pairing requires the full private binding match. Canonical DATA lookup80 is reviewed at1cf1862 with independent42; current CI is pending. Planning prose now reflects actual merged components and open production/preview/recovery gates; no package or acceptance promotion.

- Merge callback gate PR78 asaf9d7e2 after exact Claude PASS04c55a1/independent67 and inspected full40351776875:1291 cases with no failures/errors/skips. Preserve exact fresh-ack callback, retained/unknown refusal, bounded synchronous close/worker behavior and actual ambient JTA proof. No production graph/composite activation; next exact-credential pairing/data lookup and full producer/node/output/async/current-authority gates remain open.

- Merge live identity PR77 as53eb019 after GPT exact PASS7e2a9fc, independent53/final11 and inspected full1273 plus real-provider CI. Current typed-workload/binding/grant checks remain a synchronous identity component; no production bridge/model/graph allowance. Open separate JDBC callback gate78 with stable17/combined55 and two intended negative controls; exact final peer review/current CI remain pending. No package or acceptance promotion.

- Merge V76 facts PR74 as561c691 after exact Claude PASS3f37b3b/6419457964 and an independent60-case Java21/PG16 rerun. Full40265793674 remains verified at1262 cases with zero failures/errors/skips. V76 retains data only; next separate owned JDBC entry gate and live identity check do not yet enable production dispatch. V77-V80, composite current authority, Flow/recovery and package acceptance remain gated.

- Verify V76 candidate74 exact3f37b3b with full40265793674:175 XML suites/1262 cases, zero failures/errors/skips. Inspect actual schema20, older parent22, witness18, startup4, default-denial1 and recovery1 plus archive digest/CRC and matching tested tree. Preserve initial failed CI and local missing-guard evidence. Claude exact review remains blocked by its session limit until09:50 Dubai; do not merge or activate V76 before that gate. Admission73 postmerge full/provider checks pass; no package/acceptance promotion.

- Merge admission-witness73 asa87fc292 after exact GPT review9ce4279 and inspected full1242-case plus real-provider CI. Retain per-call isolated closure and default-denying wiring; live identity fence/graph adoption remain future gates. V76 PR74 corrects two earlier parent TRUNCATE probes for new FK descendants and passes combined60 locally; current CI and exact Claude review are pending after a recorded failed run.

- Push admission-witness PR73 (9ce4279, GPT exact PASS and independent42 tests) and V76 data-only facts PR74 (d83b15f, author19 tests and two intended missing-guard controls). Current CI remains pending; Claude exact review for74 is delayed by its session limit until09:50 Dubai. Neither candidate is merged or activated; backend/current authority/package acceptance remain gated. Main5083e99 full40258664514 passes.

- Merge checkpoint-profile PR67 as mainb02875f after exact Claude PASS a01b8e1/6405038545 and full40245351968. Inspected actual artifact12617854320:170 XML suites/1183 executed cases with zero failures/errors/skips, schema10/24 observations, migration/real-saver/grants4, default admission refusal1, exact migration/vendor digests and tested-tree/archive integrity. Runtime DDL removal/current-profile startup proof is the next Claude-owned handoff; credentials, execution authority and package/acceptance closure remain gated.

- Prepare reserved V75_1 checkpoint-profile ownership from the existing proposal, preserving compatible history and refusing unsupported/partial/locked stores. Tighten column-collation validation after a real probe exposed acceptance outside the pinned default profile. Local PostgreSQL16/Java21 passes schema10/24 observations and ordered migration/real-saver/grants4; retain actual XML and case JSON in CI. Runtime DDL removal, data-source/current-authority adoption and package closure remain separate; exact review/current CI/merge are pending.

- Merge execution contract PR66 as main1773a00 after exact Claude PASS de853bb/6404473197 and full40241496066. Independent contracts200/actual serializer1/Python88/generated checks pass; actual CI confirms contract4, serializer1 and negative default admission wiring1. No backend, graph callback, schema or current-authority activation. Reserve additive V75_1 for the following checkpoint-profile migration, preserving existing V76-V80 preparation and all applied versions. Package/acceptance status stays partial.

- Prepare the existing execution entry/outcome/transient-fence contract on current main without importing older schema/budget/gateway changes. Reuse the merged output fingerprint and retain default admission refusal. All200 contracts pass (four new cases with independent Python vectors), plus the actual serializer/outcome compatibility case. ADR0028 stays Proposed; no backend, callback, schema, current authority or package/acceptance activation. Peer review and current CI remain pending.

- Merge V75 PR65 as main2beaeb4 after exact Claude PASS cd15aca/6404200386, an independent six-case PostgreSQL16 rerun and full40239627410 SUCCESS. Actual logs confirm migration6, appendOnce22 and acceptance/dependency/Python88 checks. The unchanged agreed DDL and fresh-role grant procedure are integrated; production credentials, audit CDI/data-source adoption, current authority and effect delivery remain gated. No package or acceptance promotion.

- Prepare additive V75 source-binding activation after the merged fixture prerequisite. Preserve the agreed DDL body and existing audit history; provide a dedicated writer/read grant template without changing application credentials or data-source wiring. Six local PostgreSQL16/Java21 tests pass for fresh/concurrent append, nonempty upgrade, conflicting-schema rollback, retained-reference guards, append-only protection and least-privilege denial. Exact peer review, required CI and integration remain pending; WP-108 and acceptance stay partial.

- Integrate Claude audit fixture PR64 at exact 6610bf1 as main 63291fa after GPT PASS 6403925367 and full build 40237862668. Pin private proposal tests to the V74 foundation; shared TRUNCATE probes roll back and require the intended parent guard. Independent PostgreSQL16 proof: 31 tests pass both before and with the privately applied V75 proposal; disabling the two parent guards produces two intended assertion failures; restoring them returns all5 append-only cases to PASS. Production V75 and permissions remain the next separate increment; no package or acceptance promotion.

- Verify merged CI changes with successful full main builds 40237056066/40237188334 and the actual Linux scoped reactor command, including application/process tests. Preserve the failed Windows-native and initial container-network attempts as limitations, not passing evidence.

- Preserve LF checkout bytes for the SHA-256-pinned V75 proposal fixture. A Linux build from a Windows checkout reproduced a hash mismatch caused by CRLF conversion; the stored proposal and its expected digest are unchanged.

- Integrate the scoped-CI proposal from PR62 with conservative fixes: only Markdown prose skips Maven; unknown module/source paths, test-source changes, the acceptance gate's oa-testkit module and executable documentation fixtures force a full build. Include both sides of renames and fall back to a full build for missing scope output. Run the resolved-dependency policy only with a full reactor because scoped builds lack its required per-module files. Pull requests retain generated-document, compose and Python checks; main always runs the full build and acceptance/dependency checks. Scope selection has 12 regression tests, including an actual Git rename; all 88 Python tests pass locally.

- Integrate Claude PR58 exactf459e06 as main998b501 after GPT exact-head PASS 6318839679 (OpenCode continuing the GPT role), required check 39538682540 and independent strict real-Keycloak BOTH 39619195441 at the same SHA. Central append-once consumer and shared fingerprint adoption land as partial, NOT activated: V75 stays proposed with no role/CDI/runtime activation. One P3 correction recorded for Claude (handoff claims inspector 26 tests; suite/CI prove 25). Existing V73/V74 attribution branch reconciles the new main at 733d290/1396147 with unchanged product bytes versus tested2f59db1; its sole implementation PR awaits fresh combined CI and exact Claude review.

- Reconcile the existing V73/V74 attribution branch with reviewed merged membership/main and current serialized tracking. Runtime/test/migration/build/CI bytes remain identical to tested2f59db1; no backend results are relabeled or repeated. Legacy production admission stays unwired; queue behind current PR57 and require its later current-base/peer/CI gate. No new feature or V75 activation.

- Review Claude PR58 exact87bc20e: request changes for bounded-pool commit-readback exhaustion and valid source/chain lock-argument aliasing. Independent detached current-class JDBC/input probes and compilation pass; no local PostgreSQL execution claimed. Publish concrete fixes/tests/role-boundary handoff. GPT PR57 remains5bb6a81/all current full+strict green, pending exact Claude review.

- Verify final DATA-only attribution product2f59db1/BOTH39523039921: all155 actual suites EACH/storage17/negative production-default wiring1 and strict real providers/core pass zero skips; exact current methods/archives/digests independently inspected. Canonical storage stays available and legacy admission stays unsatisfied. Exact peer/current PR/prerequisite integration and actual consumer/authority/V75/package gates remain open.

- Keep historical attribution from activating legacy admission before the current execution gate: expose concrete/canonical storage only and remove the intermediate default legacy producer. Production-default boot regression now requires the admission port to stay unsatisfied. Intermediate26bdce4 strict CI passed155 suites EACH, but its positive-default proof is historical; nine local controls/app compile pass, fresh final-boundary Linux evidence required. SQL/tests/consumer ownership and package gates retained.

- Fix combined candidate CDI ambiguity by exposing concrete/canonical storage separately and providing a default legacy admission-port bridge. Preserve Claude's test override and add a production-default wiring boot case. be45a08 strict CI failed before app discovery; failed artifacts retained, no SQL/provider pass claimed. Nine local controls/app compilation pass; fresh exact Linux/strict evidence pending.

- Normally refresh the queued V73/V74 attribution increment onto integrated Claude #53/current membership correction. Existing resource-store/schema/test bytes remain unchanged; previousf25de24 actual142-suite proof stays historical. Fresh combined-head compile/SQL/provider and current review/PR gates required; no consumer or V75 activation.

- Normally reconcile the pending membership correction with reviewed integrated Claude #53/current main. No membership runtime/test/migration bytes change; previous5522bce strict success remains historical. Fresh combined-head CI and exact Claude review are required before #55 advances.

- Integrate PR55 exactc9ce389 as mainf840b06 after Claude6306699855 and current full39534066349/strict BOTH39534071955. Retain pinned groups and add complete recorded-match/finalized-read/replay fences; actual SQL16/provider/core evidence inspected. Actual main postmerge full39534880880/BOTH39534883157 SUCCESS with logs/provider XML/strict checks/digests inspected; full T13 remains partial. Next sole WP108 PR57 reconciles the existing shared fingerprint at5bb6a81, current full39535234967/BOTH39535234969 SUCCESS with154 actual XML EACH/zero skips inspected; exact current review still gates merge.

- Integrate PR55 exactc9ce389 as mainf840b06 after Claude6306699855 and current full/real-Keycloak checks. Retain matched-slot completeness and finalized-read/replay bounds; full T13 remains partial. Postmerge checks running. Reconcile the existing tiny WP108 output fingerprint candidate with merged membership/main; new combined execution and exact peer review pending.

- Reconcile the final serialized main tracking into the held membership candidate, preserving all current proof and queue boundaries. This is documentation only; runtime/test/migration bytes remain at verifieddb43b3b. Hold this final review head and main tracking stable until exact peer/current required PR gates clear.

- Verify current-main membership correction atdb43b3b/BOTH39521278476: SQL16 EACH, integrated inspector24/governed-model12/rule3/actual provider1 and real standard3/delegation8/core1 pass zero skips. Actual full logs/archived provider XML/digests/strict checkers inspected. Final documented candidate needs exact Claude review/current PR checks before #55 advances; unchanged tests/migrations and partial acceptance retained.

- Queue a small WP-108 prerequisite integration candidate from existing preparation: V73 immutable resource attribution and V74 canonical occurrence/JDBC store, preserving original call identity across SDK counter changes. Nine selected source/test files retain preparation bytes;193 contracts/nine focused controls/app compile pass,17 SQL cases await fresh exact Linux execution. Retain app/module Surefire XML in strict provider artifacts. No dispatcher/graph/audit-consumer activation, second implementation PR or package promotion.

- WP-107 review N1/N2 candidate: additive72.1 requires full retained-match coverage at advancement, allowing only earlier exclusive memberships as substitutes; finalized reads/replay refuse pending rows. V71/V72 remain unchanged and their prior direct-SQL wording is narrowed to late additions/per-member checks. Five new SQL cases (16 total), app test compilation/tools76 pass; exactc2972fa BOTH39518381586 SUCCESS, actual full logs confirm SQL16 and all module totals zero skips EACH; archived exact real-provider3/8/core1 XML and strict checkers independently verified. SQL method XML is not archived by the parent workflow. Final candidate peer review/current required PR checks pending. #55 remains frozen at reviewed ea56f33; no package promotion.

- WP-107 pinned group/snapshot/V71-V72 ordered-membership increment is refreshed onto reviewed main54 for exact-head peer/full/strict review.192 contracts/25 selected cases/app test compilation/tools76/four OWS fixtures pass locally; full selector/coordinator/FIFO/restore/T13 and package gates remain open. Main54 postmerge full and both strict provider/process lanes passed; finished logs inspected.

- Combined preparation on reviewed #48 and effect/process-CI foundation passed BOTH39381927960 atc1fd835: snapshots6/group SQL11/actual SDK-JPA11/receipt recovery5/restore gate5/CDI tracing3/core effect process recovery1/standard3/native8, zero skips and strict checkers. Production group selector/coordinator/execution/recovery/T13 remains open.

- WP-107 preparation: derive pinned group snapshots from actual SDK listen tasks, scoped approved producer routes and explicit correlation parameters. Preserve duplicate ordinals and empty ANY arrays. Forty selected plain cases pass; full consumer/coordinator/recovery remains open. Exact V72 product4d75161 independently passed both39379015967 lanes (11 SQL cases, zero skips).

- WP-107 storage hardening: additive V72 closes late membership INSERTs after selection and binds members to the original match set; readback also fails closed on changed membership. Thirty local plain cases pass; eleven SQL cases compile and await fresh CI. V71 remains unchanged.

- WP-107 preparation: centrally sequenced V71 and task-fenced group selection retain configuration/deadline, ordered receipt memberships and stream/stop roles. Twenty-eight plain cases/app compile pass; ten PostgreSQL cases await CI. Selection grants no callback, checkpoint or ACK; full consumer/recovery/T13 stays open.

- WP-107 preparation: framework-free listen-group identities preserve duplicate filter ordinals, data/until roles, wildcard ANY and pinned configuration. Receipt-match classification excludes stopping events from data; it grants no execution permission. All172 contract tests pass, including8 new cases; durable storage/coordinator and full acceptance remain open.

- WP-107 preparation: four actual SDK full-workflow listen-group cases establish ALL order/overlap and negative controls for ANY post-completion mutation and concurrent foreach/until completion. All four fixtures validate; these observations do not close durable delivery, FIFO or T13 gates.

- WP-108 CI preparation: run the existing real process-death charge/compensation IT beside each real provider lane, and refuse missing/skipped/contradictory recovery XML. Eight new verifier cases and all76 tooling tests pass; new product process execution still needs fresh Linux CI. No acceptance promotion.

- Publish the isolated retained-output fingerprint proposal and local verification (186 contracts/four controls) for the requested Claude consumer; implementation remains queued4e45373. Membership correction e6a873b awaits exact peer/current checks after combined-product SQL16 pass. Separate attribution bridge fix26bdce4 is under fresh CI after a retained CDI discovery failure; no activation or package promotion.

- Integrate Claude #53 exacta71fb1f as main2f10662 after GPT exact-head review6304838496 and full/real-Keycloak CI. Consolidates closed #56, corrects read-only research inspection and gates all current model nodes through audited PEP with no allowing production context. Actual provider one-request failure test passes; concrete-provider architecture-rule gap remains a nonblocking follow-up. Publish the concrete V75 central-audit transaction/duplicate/chain/role/test handoff; activation/consumer/shared execution and package gates remain partial. Postmerge full39520880781/BOTH39520880697 and documentationc20d37f build39521142788 SUCCESS; actual logs/archived provider XML/strict checkers inspected.

- WP-108 shared audit contract: immutable source/receipt values and a versioned canonical draft fingerprint for future idempotent projection. All183 contract tests pass including9 new cases; application tests compile/tools76 pass. No audit SQL, projector/outbox/relay, dispatch permission or package/acceptance promotion.

- WP-108 preparation: refuse interrupted callers before effect/refund dispatch and after blocking ledger/cleanup work, preserving committed facts and original recovery keys. Six local interruption cases pass; four new PostgreSQL cases compile and need fresh Linux execution. This is cooperative thread handling, not complete Flow/owner/authority cancellation.

- WP-108 CI preparation: run the existing real process-death charge/compensation IT beside each real provider lane, and refuse missing/skipped/contradictory recovery XML. All76 tooling tests pass. Exact4325993 passed BOTH39381656269 with process recovery1/SQL guards5/effects12/CDI tracing3/standard3/native8, zero skips and strict checks. Full governed Flow/owner/partition/peer gates remain; no acceptance promotion.

- WP-108/WP-112 preparation: bounded, content-free OTel effect/dispatch/lookup/compensation spans, disabled SDK by default with runtime collector opt-in. Five SDK/fault and three real ledger/CDI cases pass in current Linux evidence above. Governed Flow/adapter/collector/complete distributed tracing remain open.

- WP-108 preparation: additive V70 protects effect intent identity, replay contract, irreversible state/attempt evidence and retained journal/compensation links. Five direct-SQL cases and original core process recovery execute in current Linux evidence above. Final peer/full CI, governed adapter/manual/audit/owner/Flow recovery gates remain open.

This log records implemented capabilities and their requirement coverage. Entries describe unreleased development work.
A merged component or passing acceptance case does not close an entire platform requirement. The full
remaining checklist is [TODO.md](TODO.md); implementation status is maintained in
[work-packages.json](docs/plan/work-packages.json).

## [Unreleased]

- WP-108 partial: publish an explicit effect scope/registry-owner contract that preserves existing recovery keys, plus the current authority/manual-policy/central-audit handoff. Four scope and two existing key cases pass; no effect/operator consumer or package gate is activated by the value type.

- WP-103 shared contract preparation: immutable registry/task resource-call attribution, stable invocation keys distinct from target/request fingerprints, governed thread derivation and verified issuer/jti binding port. All170 contracts pass; backend/admission/dispatch and actual workflow success remain open.

- WP-107 integration: #48 merged after exact Claude approval, independent full verification and green full/strict provider CI. Group/coordinator/FIFO/foreach/until/loop, process/partition, effect and full T13 gates remain partial.

- WP-107 partial: repair actual committed receipt ACKs in bounded batches before the reconciler starts restored work, preserving identity/cancellation gates and failed-repair readiness. Ten new database/reader/lifecycle cases compile; execution/review and complete distributed consumer/T13 gates remain open.

- WP-107 partial: compose asynchronous receipt ACK and a fresh ownership check after actual JPA commit and before protected SDK advancement. Bounded workers leave unmarked tasks immediate;24 plain cases pass and three new real-SDK ACK/owner-loss cases compile. Current database execution, pre-restore/group recovery and full T13 remain open.

- WP-107 partial: decode complete stored CloudEvents for the future SDK consumer, validate receipt metadata and preserve precise JSON/binary/null data. Six decoder and two parser unit cases pass; production coordinator/recovery and full T13 remain open.

- WP-107 partial: add task-fenced matching, one audited dispatch permission per epoch and historical proof-based ACK using short transactions and one connection at a time. Unknown replies retain reservations without returning permission. Production coordinator/restore and complete T13 remain open; V69/database evidence and reciprocal review are required.

- WP-107 partial: add task-scoped renewable event ownership and fence the actual SDK checkpoint with its owner/epoch. The producer/consumer coordinator, ACK-before-advance, restore/partition proof and full T13 remain open; no consumer is activated.

- WP-107 partial: add retained wait identities, dispatch reservations and a checkpoint-proof acknowledgement port. SDK delivery, lost-claim fencing and full T13 remain open; the protocol is not activated.

- WP-102 partial: add V65 immutable issuer/jti-to-grant schema with tenant binding and expiry retention. Product admission integration and native-provider lifetime compatibility remain open.
- WP-107 partial: guard actual Flow checkpoint commits against swallowed lifecycle failures; hold failed/unknown writes, report readiness down and refuse new starts until restore. Durable inbox acknowledgement and full T13 remain open.

- **WP-107 (partial):** persist normalized events before matching and record durable
  wait signals with duplicate, expiry and cancellation checks. Durable engine
  delivery/replay remains open; T13 is not promoted.
- **WP-107 ingress (partial):** receive bounded structured CloudEvents through
  verified caller, scoped policy and deployment-bound routes; preserve complete
  canonical envelopes with audit and receipt metrics. Engine consumption remains
  a separate durable acknowledgement gate.

### Added

- **Effect ledger (WP-108, partial):** JDBC write-ahead intent and stable operation keys, request conflict
  rejection, cross-process execution claims, bounded unknown-outcome replay, compensation intents and an
  internal durable manual-resolution queue. Added database and process-death T03 tests. Governed adapter,
  manual-decision, central-audit and lifecycle integration remain open; CI and Claude review are required
  before merge. [Scope and handoff](docs/handoffs/gpt/WP-108.md).

### Tracking

- Integrate the reviewed PR #39 audit-caller increment into PR #40; run its real
  Keycloak characterization on both pinned issuers separately from strict authority
  contract gates. Skips and characterization never close WP-102.

- **WP-102 H1/H2 (partial):** retain strict signed actor proof, reject actorless standard exchange,
  and add an isolated real-Keycloak Java CI matrix with missing/skipped-evidence rejection. The Java
  identity tests remain Claude-owned; harness availability does not complete provider interoperability.

- Split the 42 unfinished packages into GPT and Claude backlogs with 21 primary owners each, reciprocal
  reviews, explicit prerequisite handoffs and exclusive write areas. Generate both queues in TODO.md and
  validate assignment coverage, reviewer independence, dependency cycles and reserved-path conflicts.
  These assignments do not add product capability or acceptance coverage.
- Reconcile all 53 work packages and 64 requirements with merged code and evidence. Keep component completion,
  partial integration and unstarted work explicit. Generate TODO.md and progress pages from the plan JSON;
  CI checks for stale documents and unsupported completion claims.
- Keep the real Keycloak integration gate open for WP-102 despite passing fake-IdP acceptance tests.
- Assign emergency-revocation acceptance T23 to WP-305, where cross-task containment is implemented.

## 2026-10-19

### Added

- **Identity and delegated authority (WP-102, partial):** verified JWT callers, scoped/expiring delegation grants,
  RFC 8693 exchange client, replay protection, resource-bound credentials, mounted-secret rotation and
  reauthorization after long waits. T07 runs against in-process stand-ins. Real Keycloak actor/claim/lifetime
  interoperability remains a closure gate. [PR #31](https://github.com/bassemZohdy/open-workflow-agentic/pull/31),
  [identity evidence](docs/identity.md).
- **Policy core (WP-103, partial):** Cedar decisions with policy/version attribution, fail-closed enforcement,
  narrow operator recovery and bounded calls. Production boundary wiring, default-deny proof and approved-state
  freshness remain open. [PR #11](https://github.com/bassemZohdy/open-workflow-agentic/pull/11), [policy](docs/policy.md).
- **Workflow and agent-output validation (WP-104):** OWS input validation, referenced tool/agent/policy checks,
  structured output validation, forbidden-action refusal and escalation authorization. T01 is automated.
  [PR #14](https://github.com/bassemZohdy/open-workflow-agentic/pull/14).
- **Lifecycle core (WP-105a/b/c/d; WP-105 remains partial):** canonical registry and history, idempotent API start,
  linked agent tasks, transactional outbox, task-view projection, engine status sync, warmup-aware reconciliation,
  and inspect/cancel/retry/pause/recover APIs. Persistent business-effect idempotency and full T16 closure remain
  open. [PR #10](https://github.com/bassemZohdy/open-workflow-agentic/pull/10),
  [#18](https://github.com/bassemZohdy/open-workflow-agentic/pull/18),
  [#20](https://github.com/bassemZohdy/open-workflow-agentic/pull/20),
  [#22](https://github.com/bassemZohdy/open-workflow-agentic/pull/22), [lifecycle](docs/lifecycle.md).
- **Audit component (WP-111):** append-only hash-chained events and head log, scheduled/on-demand verification,
  payload retention/redaction/reference forms, version stamps and query APIs. Verified caller/PDP wiring,
  deployment roles, archival and external anchoring remain integration/operations tasks.
  [PR #12](https://github.com/bassemZohdy/open-workflow-agentic/pull/12), [audit](docs/audit.md).

### Changed

- Group runnable/planned applications under `apps/` and reusable/test modules under `modules/`; update POMs,
  CI paths, acceptance evidence and documentation. Preserve artifact IDs and production implementation.
  [PR #33](https://github.com/bassemZohdy/open-workflow-agentic/pull/33), [structure](docs/repository-structure.md).
- Remove the five exploratory spike projects while keeping reports, measurements, confidence labels and ADRs.
  [PR #32](https://github.com/bassemZohdy/open-workflow-agentic/pull/32).
- Record owner decisions in ADRs 0019â€“0023, including recovery liveness, secret-store conditions and version policy.
  [PR #27](https://github.com/bassemZohdy/open-workflow-agentic/pull/27).
- Retain the bridge findings/ADR and the partial A2A wiring diagnosis as research outcomes; a working product A2A
  server/client is still required in WP-207. [PR #8](https://github.com/bassemZohdy/open-workflow-agentic/pull/8),
  [#29](https://github.com/bassemZohdy/open-workflow-agentic/pull/29).

### Fixed

- Bound in-flight policy decisions, assert JSON Schema date-time formats and enforce the canonical timestamp profile.
  [PR #15](https://github.com/bassemZohdy/open-workflow-agentic/pull/15),
  [#19](https://github.com/bassemZohdy/open-workflow-agentic/pull/19).
- Unify the audit sink and make the PEP choose an explicit concurrency bound of 64.
  [PR #21](https://github.com/bassemZohdy/open-workflow-agentic/pull/21).
- Link retries of FAILED instances and cancel STARTING instances directly when no engine instance exists.
  [PR #28](https://github.com/bassemZohdy/open-workflow-agentic/pull/28).
- Record the bridge recovery constraints: readiness stays false on reconciliation failure, and arbitrary runtime
  failures must not trigger replay from the start. The prototype was removed; its findings remain in the report,
  while maintained recovery code/tests live in the lifecycle modules.
  [PR #17](https://github.com/bassemZohdy/open-workflow-agentic/pull/17), [bridge findings](docs/spikes/WP-005-workflow-to-agent-bridge.md).

## 2026-10-18

### Added

- Repository/module foundations, API conventions, ArchUnit rules, dependency/licence checks, development Postgres
  and the acceptance/fault-injection testkit (WP-001/WP-002).
  [PR #1](https://github.com/bassemZohdy/open-workflow-agentic/pull/1).
- Collect acceptance results from both Surefire and Failsafe, including missing/skipped/failed integration-test
  regression checks (WP-008). [PR #2](https://github.com/bassemZohdy/open-workflow-agentic/pull/2).
- Framework-free contracts, schemas, identifiers, canonical encoding, audit hashes and service ports (WP-101).
  [PR #3](https://github.com/bassemZohdy/open-workflow-agentic/pull/3), [contracts](docs/contracts.md).
- Quarkus Flow findings and lifecycle ADR 0019; reconcile the plan with engine restore/retry behavior.
  [PR #5](https://github.com/bassemZohdy/open-workflow-agentic/pull/5),
  [#7](https://github.com/bassemZohdy/open-workflow-agentic/pull/7).
- Retain LangGraph4j durability, identity/policy/secrets and MCP/A2A/AG-UI research findings in
  [spike reports](docs/spikes/) and [ADRs](docs/adr/README.md). Reported unknowns remain product validation tasks.

## Implemented portions of requirements â€” 2026-10-19 snapshot

Every row below has concrete implementation evidence. Coverage is **partial** for the full requirement; the
remaining scope is tracked by its owning work packages in TODO.md. Research-only findings are excluded from
product implementation claims. T01 and T07 are the only 2 of 39 acceptance cases currently recorded as automated.

| Requirement | Implemented scope and remaining boundary | Evidence |
|---|---|---|
| M01 Process semantics and definition validation | Validated task contracts, OWS inputs/references and structured outputs; T01 automated. | [docs/contracts.md](docs/contracts.md) |
| M02 Bounded agent autonomy and risk controls | Bounded contract fields, forbidden actions and escalation authorization; continuous budget enforcement remains open. | [docs/contracts.md](docs/contracts.md) |
| M03 Durable execution and checkpoint recovery | PostgreSQL workflow/graph state, restart reconciliation and absolute wait-deadline support exist; effects and multi-node proof remain open. | [docs/lifecycle.md](docs/lifecycle.md) |
| M04 Durable triggers, timers and lifecycle | Idempotent API start and lifecycle recovery exist; event/timer/expiry coverage remains incomplete. | [docs/lifecycle.md](docs/lifecycle.md) |
| M06 Events, business effects and compensation | Transactional outbox and idempotent lifecycle requests exist; business-effect ledger, inbox and compensation remain open. | [docs/lifecycle.md](docs/lifecycle.md) |
| M07 Resilience under dependency failure and overload | Policy timeout/bulkhead and explicit engine retry settings exist; general dependency/overload guarantees remain open. | [docs/policy.md](docs/policy.md) |
| M09 Version-safe in-flight work | Workflow definition identity/version is pinned in lifecycle records; complete artifact pinning and rolling-version proof remain open. | [docs/lifecycle.md](docs/lifecycle.md) |
| M11 Authentication, delegation and secret handling | Delegation, JWT verification, token exchange, replay checks, secrets and wait reauthorization exist; T07 uses fakes and the real Keycloak closure gate remains open. | [docs/identity.md](docs/identity.md) |
| M12 External policy and safe denial | Cedar PEP/PDP, attributable decisions, safe denial/recovery and bounded policy calls exist; non-bypass wiring and production snapshot freshness remain open. | [docs/policy.md](docs/policy.md) |
| M13 Data security, privacy and location | Audit redaction/reference retention and secure secret-file handling exist; platform-wide encryption, erasure and processing-location controls remain open. | [docs/audit.md](docs/audit.md), [docs/identity.md](docs/identity.md) |
| M14 Untrusted inputs and optional code execution | Malformed output, forbidden actions and unauthorized escalation are rejected by T01; retrieval/tool defense and optional sandboxing remain open. | [docs/contracts.md](docs/contracts.md) |
| M15 Auditability and AI decision provenance | Durable hash-chained audit, retention, stamps and query APIs exist; all future decision/effect producers and production access still require integration. | [docs/audit.md](docs/audit.md) |
| M16 Observability and operational APIs | Operator lifecycle APIs, correlation IDs, health and metrics exist; correlated distributed tracing remains open. | [docs/lifecycle.md](docs/lifecycle.md) |
| M19 Agent context, memory and state governance | PostgreSQL graph checkpoints exist; governed memory, full isolation, retention and growth controls remain open. | [docs/architecture.md](docs/architecture.md) |
| M22 Provider-independent deployability | Java/Quarkus/PostgreSQL and a configurable model endpoint form a self-hostable foundation; OCI/cluster and offline profiles remain unproved. | [README.md](README.md) |
| M24 Tenant safety where used | Verified tenant context, tenant-scoped grants and lifecycle ownership filters exist; multi-tenant RLS, memory/state/telemetry isolation and the complete isolation suite remain open. | [docs/identity.md](docs/identity.md), [docs/lifecycle.md](docs/lifecycle.md) |
| M25 Reproducible authoring and testable artifacts | Version-controlled workflow/contract artifacts and build/load validation exist; reusable authoring, packaging and governed promotion remain open. | [README.md](README.md), [docs/contracts.md](docs/contracts.md) |
| M26 Separation of authority | Execution/control code and dependency boundaries are enforced; production author/approver/runtime privilege separation remains open. | [docs/repository-structure.md](docs/repository-structure.md) |
| M27 Safe autonomy during administration outages | Execution has no control-module dependency and narrow recovery survives policy ERROR; approved-state freshness and administration-outage tests remain open. | [docs/policy.md](docs/policy.md) |
| M28 Recoverable canonical progress and reconciliation | Canonical lifecycle registry, task state, outbox projection and reconciler exist; complete effect-aware recovery/T16 remain open. | [docs/lifecycle.md](docs/lifecycle.md) |
| M29 Goal-driven planning and adaptive execution | A fixed plan/draft/review sample graph exists; governed adaptive planning and re-planning remain open. | [docs/architecture.md](docs/architecture.md) |
| M34 Model choice and routing per agent task | One sample model endpoint/name is configurable; approved per-task routing, fallback and evaluation remain open. | [README.md](README.md) |
| H01 Open Workflow Specification alignment | OWS definitions and schema/reference validation exist; conformance and round-trip loss are not measured. | [README.md](README.md) |
| H02 Runtime and framework independence | Framework-free contracts and narrow model/workflow boundaries exist; full adapter/artifact portability remains open. | [docs/contracts.md](docs/contracts.md) |
| H12 Developer lifecycle and packaging UX | Acceptance fixtures, fault injection, validation and CI checks exist; authoring/package/promotion UX remains open. | [docs/testing.md](docs/testing.md) |
| H23 Open-source licensing and reuse freedom | Dependency/licence policy and permissive-source checks run in CI; full supply-chain/SBOM evidence remains open. | [CONTRIBUTING.md](CONTRIBUTING.md) |
| H24 Simple, clean and maintainable architecture | Applications/libraries are grouped, contracts remain framework-free and boundaries are checked; broader maintainability is reviewed as features land. | [docs/repository-structure.md](docs/repository-structure.md) |

Validation baseline: [CI build #78](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39247480310) passed the Java 21 build, boot/process recovery tests, acceptance reporting, generated-document check, Compose validation and dependency policy. No release or production-readiness certification is implied by this snapshot.
