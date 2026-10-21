# Audit trail and provenance (WP-111)

**Status:** implemented in `oa-runtime` (package `runtime.audit`) · **Requirement:** [M15](requirements/README.md) (tamper-evident, queryable records; payloads redacted or referenced per retention) · **Depends on:** [contracts](contracts.md) §6 (`AuditEvent`, `AuditHash`, `ChainVerification`) · **Decisions:** [ADR 0011](adr/0011-use-one-postgresql-database-for-all-durable-state.md) (one database), [ADR 0017](adr/0017-one-codebase-two-deployable-roles-with-enforced-boundaries.md) (separation below the code)

The audit trail records who decided what, with which versions, and what happened to each external effect. It is an append-only, hash-chained log in PostgreSQL. Records are never changed; a payload can be deleted under a retention rule without breaking the chain.

## 1. The chain model

An event is an `AuditEvent` from the contracts: id (ULID), `sequence` (1-based, per chain), `occurredAt`, `actor`, `action`, `resourceType`, `resourceId`, `attributes`, `previousHash` and `hash`. `AuditHash` computes `hash` as SHA-256 over a length-prefixed encoding of every field except `hash`. The first event of a chain links to `AuditHash.GENESIS` (64 zeros).

The contract has no chain, instance, correlation, version or payload fields. These travel in `attributes` under reserved keys, so `AuditHash` covers them and no contract changes were needed:

| Key | Meaning |
|---|---|
| `oa.chain` | Chain id (`[A-Za-z0-9._:-]{1,128}`); default `default`. Sequences are per chain. |
| `oa.tenant`, `oa.instance`, `oa.correlation` | Scope and request identifiers (optional). |
| `stamp.model`, `stamp.prompt`, `stamp.tool`, `stamp.policy`, `stamp.policy-set`, `stamp.definition` | Version stamps (section 5). |
| `oa.payload.form` | `STORE_INLINE`, `STORE_REDACTED` or `STORE_REFERENCE_ONLY`. |
| `oa.payload.ref`, `oa.payload.digest` | Reference id and SHA-256 digest (reference form only). |
| `field.<path>` | A payload field, inline or as a salted digest (section 4). |

The shared `runtime.audit.AuditSink` is the write port for the trail and for policy decisions. WP-103 converts each PEP decision to an `AuditEvent` with action `policy.decision`; the event records the actor and resource, outcome, deciding component, policy id/version, action kind, task id when present, and whether safe recovery produced it. Policy context and free-form decision reasons are not copied into event attributes.

Rules that follow from the encoding:

- `occurredAt` is stored and hashed at microsecond precision, the precision of PostgreSQL. The trail truncates it before sealing, so the hash verifies after a round trip.
- The trail assigns `sequence`, `previousHash` and `hash`. Values a caller passes for them are ignored. Callers build events with `AuditDraft.of`.
- Attribute values must be non-blank (contract rule). A payload field with an empty value is refused; use the reference form for such payloads.
- At most 32 attributes per event and 512 characters per value (contract limits). Large payloads belong in the reference form.

## 2. Tables (schema `audit`)

Migrations `V30` to `V34` in `modules/oa-runtime/src/main/resources/db/migration`.

| Table | Content | Append-only |
|---|---|---|
| `audit.event` | One row per event. `chain_id`, `tenant_id`, `instance_id` and `correlation_id` are **generated columns** derived from `attributes`, so a row cannot name one instance while its hashed content names another. `UNIQUE (chain_id, seq)`. | Yes: triggers on UPDATE, DELETE and TRUNCATE |
| `audit.head_log` | One row per appended event: `(chain_id, seq, hash)`, written in the same transaction as the event. | Yes, same triggers |
| `audit.payload_blob` | Payloads of the reference form, as JSON, with their digest. | **No**: retention may delete |

The triggers call `audit.reject_change()`, which raises `insufficient_privilege` (SQLSTATE `42501`) with the message `audit table … is append-only`.

## 3. Concurrency design

`AuditTrail.append` runs one transaction per event:

1. `select pg_advisory_xact_lock(hashtextextended(chain, 0))`. The lock is per chain and held until commit.
2. Read the head of the chain: `select seq, hash … order by seq desc limit 1`.
3. Seal the event on that head with `AuditHash.seal`, insert it into `audit.event` and its head into `audit.head_log`.
4. Commit.

Because the lock lasts until commit, the next appender sees the new head. No sequence number is skipped or reused, and the chain is linked in commit order. Appends to different chains do not wait for each other unless their lock keys collide. Each call takes a connection from the data source, so the pool size bounds concurrency.

Proof: `AuditConcurrencyTest` runs 20 threads, each appending 10 events to one chain. The chain must have 200 events with sequences 1 to 200, verify with `AuditHash`, and have 200 head rows. With the lock removed, the same test fails with a `duplicate key value violates unique constraint "event_chain_seq_unique"`: the database refuses the race, and no event is silently lost or duplicated.

The verifier reads an event snapshot and the head in one `REPEATABLE READ` transaction, so an append committing during verification cannot make a healthy chain look broken.

## 4. Verification

`ChainVerifier.verify(chain)` reads the chain in order and checks, in this order:

1. Sequence continuity, previous-hash links and each event's hash, through `AuditHash.verifyChain`.
2. The number of events equals the last record of `audit.head_log`.
3. The hash of the last event equals the head record's hash.

A failure is a `ChainReport` with `intact = false`, `firstBrokenSequence`, `firstBrokenEventId` and a reason. `verifyAll()` covers every chain.

Verification runs in three ways:

- **Scheduled**: `AuditVerificationJob` is a `@Scheduled` method. It runs on the db-scheduler (quarkus-db-scheduler implements the Quarkus scheduler), so one run per schedule across instances. The cron is `agentic.audit.verify-cron` (default `0 17 * * * ?`). A broken chain is logged at ERROR.
- **On demand**: `POST /audit/verify` (section 7).
- **Tests**: `ChainVerifierTest` disables only the four append-only triggers (as an owner could), changes the table, and checks the verifier's answer. The cases: an altered row, an altered version stamp, a deleted middle row, a deleted tail row, a reordered pair, an inserted row with a valid hash but no head record, and an altered head record.

**Not detected:** an owner who rewrites the events *and* the head records from some point. Only an external anchor of the head (section 8) catches that.

## 5. Version stamps (M15, M09, M10)

`VersionStamps(model, prompt, tool, policy, policySet, definition)` writes each non-null stamp as a `stamp.*` attribute, so it is hashed with the event. `VersionStamps.ofDecision(decision)` takes the policy id and policy-set version from a `Decision` record. Absent stamps are not written. A changed stamp breaks the chain (tested).

## 6. Payload retention

`RetentionPolicy` maps an event type (the `action`) to a rule. Types without a rule use the default form. The default is `STORE_REFERENCE_ONLY`.

| Form | What the event keeps | Deletable later |
|---|---|---|
| `STORE_INLINE` | Every field as `field.<path>` | No |
| `STORE_REDACTED` | Named paths replaced by `redacted:sha256:<digest>`; other fields inline. The digest covers the deployment salt, the path and the value. | No |
| `STORE_REFERENCE_ONLY` | `oa.payload.ref` and `oa.payload.digest`; the payload is in `audit.payload_blob` | **Yes**: deleting the blob does not change the chain |

`AuditRecorder.record(AuditRequest)` applies the rule, stores the reference payload first and then appends the event. A failure in between leaves an unreferenced payload (harmless), never a reference to nothing. `payloadStatus(event)` reports `MATCHES`, `DELETED` or `DOES_NOT_MATCH` for a reference.

`AuditRecorderTest.referenceOnlyKeepsADigestAndTheChainSurvivesDeletingThePayload` deletes the payload and verifies the chain: it stays intact.

Configuration (`AuditSettings`, prefix `agentic.audit`):

```properties
agentic.audit.default-form=STORE_REFERENCE_ONLY
agentic.audit.redaction-salt=${AUDIT_REDACTION_SALT}
agentic.audit.rules."customer.updated".form=STORE_REDACTED
agentic.audit.rules."customer.updated".redacted-paths=customer.iban,customer.phone
```

Rules are validated at startup: redacted paths need the redacted form, and any redacting rule or default needs a non-blank salt. Keep the salt in a secret store. Without it, a low-entropy value could be recovered from its digest by someone who knows the value space.

## 7. Query API and access

| Endpoint | Behaviour |
|---|---|
| `GET /audit/events` | Filters `instanceId`, `correlationId`, `chainId`, `type`, `from` (inclusive) and `to` (exclusive), both RFC 3339 instants. Pages with `cursor` and `limit` (default 50, at most 200). Response: `{items, nextCursor}`; `nextCursor` is null on the last page. |
| `GET /audit/events/{eventId}` | One event. 404 if unknown, 400 if the id is not a ULID. |
| `POST /audit/verify` | Verifies every chain, or the one named by `chainId`. Response: `{intact, chains[]}`. |

Pages are keyset pages on `(chain_id, seq)`, the order in which each chain commits. A new event only gets a higher sequence in its own chain, so a page never shifts under concurrent appends. The cursor is opaque base64url text; a malformed cursor is a 400. Errors are RFC 9457 problem documents with the correlation id.

**Access (`AuditResource`, `AuditAccess`).** There is no anonymous caller (WP-102). Every endpoint first establishes who is calling, with the same `CallerContextResolver` as the instance and operations APIs (`docs/identity.md` section 2), and only then asks the access rule:

1. **Authentication.** A verified bearer token gives the caller (subject, kind, tenant, scopes). No usable credential is **401** with a `WWW-Authenticate` challenge: a missing, malformed, expired, wrongly signed, wrong-audience or delegated (`act`) token. A token that cannot be checked is 503. Only in a development or test profile with `agentic.dev.trust-caller-headers=true` do `X-Tenant` and `X-Subject` identify a caller; the headers are never read when a token is sent. Nothing is read from the trail before this step succeeds.
2. **Authorization (`AuditAccess.require(action, caller)`).** The caller must have been established by the resolver: a caller built by hand (`UNVERIFIED`) or a missing one is refused without asking any policy.
   - If a `PolicyDecisionPoint` is bound, it is asked about **the caller's own subject** (a user or an agent, as the token says) for action `audit.events.read` or `audit.chains.verify`, with the token's scopes as the policy context. Only `ALLOW` passes; `DENY`, `ERROR` and a null decision are refused, and so are scopes that cannot form a policy context. The policy is never asked about a placeholder subject.
   - Otherwise, with `agentic.dev.audit-open=true` any authenticated caller passes. That setting is for development only and is `false` by default. It removes the policy requirement, not the authentication requirement.
   - Otherwise the shared policy enforcement point decides (WP-103, `Pep.auditAccess`, a `DATA_ACCESS` decision on `audit.events.read` or `audit.chains.verify` with the caller's own subject and scopes). The bundled operations policy needs the scope `oa.audit.read` to read and `oa.audit.verify` to verify (operator-wide until reads are tenant-filtered, WP-404). Every decision, including each refusal, is a `policy.decision` audit event and is bounded like every PEP decision. DENY is **403**; an unavailable decision (no snapshot, engine down, decision not recorded) is **503** and nothing is read.

A request is therefore 401 when nobody is identified and 403 when somebody is identified and not allowed. Before WP-102 an unidentified request was 403; this is a deliberate change of status, not a change of access: such a request was never served and still is not.

`AuditDeniedByDefaultTest` checks the 401 for the three endpoints by default; `AuditCallerDefaultDenyTest` checks that an identified caller is still 403 without a policy or the dev flag. `AuditVerifiedCallerApiTest` runs real RS256 tokens through the running application with a recording policy bound. `AuditApiTest` (flag on, header seam on) checks the behaviour.

## 8. Deployment hardening

In production the application must not connect as the table owner. The owner runs migrations and can disable the triggers; the application connects as the writer role below, which cannot. The SQL is what `WriterRoleTest` proves for the writer role. It is a deployment task: run it once as an administrator, and take the passwords from a secret store.

```sql
-- Writer: the application's audit role. INSERT and SELECT only; no UPDATE, DELETE or TRUNCATE on any audit table.
create role oa_audit_writer login password '<from secret store>';
grant usage on schema audit to oa_audit_writer;
grant select, insert on audit.event, audit.head_log, audit.payload_blob to oa_audit_writer;

-- Retention: the only role that deletes payloads by reference.
create role oa_audit_retention login password '<from secret store>';
grant usage on schema audit to oa_audit_retention;
grant select, delete on audit.payload_blob to oa_audit_retention;

-- Reader: verification and the query API.
create role oa_audit_reader login password '<from secret store>';
grant usage on schema audit to oa_audit_reader;
grant select on audit.event, audit.head_log to oa_audit_reader;
```

Two layers protect the events. The privilege layer (the writer has no UPDATE or DELETE) stops a compromised application from changing rows through SQL. The trigger layer stops the same for any role that lacks the privilege to disable triggers. A role that owns the tables can still disable the triggers (`ALTER TABLE … DISABLE TRIGGER`); the owner must be a separate administrative identity that the application never uses, and the head anchor (below) is the only check against it.

Operational rules:

- Run migrations as the owner, never as `oa_audit_writer`.
- The application needs a separate data source for the audit writer. Today `app-runtime` uses one data source for everything (ADR 0011); splitting it is part of WP-304 (separation of authority).
- `WriterRoleTest` creates and drops the role `oa_audit_writer_test` on the server it runs against. Do not run the test against a shared production cluster.
- The reader and retention roles above are not exercised by a test yet.

## 9. Tests

| Test | Proves |
|---|---|
| `AuditTrailTest` | Sealing on the head, the existing `AuditHash`, placeholder fields ignored, microsecond precision, `record` as the sink entry point, default chain, invalid chain refused |
| `AppendOnlyTest` | Plain SQL UPDATE, DELETE and TRUNCATE on `audit.event` and `audit.head_log` fail with `42501` and the append-only message |
| `WriterRoleTest` | The writer role appends; it cannot update, delete or truncate events, head records or payloads (`permission denied`) |
| `AuditConcurrencyTest` | 20 appenders × 10 events: gap-free, linked, 200 head rows (fails without the lock) |
| `ChainVerifierTest` | The tamper cases of section 4 |
| `RetentionPolicyTest` | Rule validation, salt requirement, redacted-path form |
| `AuditRecorderTest` | Each retention form, salted redaction, reference deletion keeps the chain, stamps hashed, envelope written, tamper of an inline field detected |
| `AuditQueryTest` | Filters, time range (inclusive start, exclusive end), cursor paging without gaps or repeats, malformed cursor, lookup by id |
| `AuditAccessTest` | Deny by default, dev flag needs an identified caller, bound policy allow and deny, null decision, the policy is asked about the caller's own subject and scopes (a user and an agent), a hand-built or missing caller is refused before the policy is asked, malformed scopes are a denial |
| `AuditDeniedByDefaultTest` (app-runtime) | 401 problems for the three endpoints when nobody is identified (the default) |
| `AuditCallerDefaultDenyTest` (app-runtime) | An identified caller is 403 without a policy or the dev flag; an incomplete or missing identity is 401 even with the header seam on |
| `AuditVerifiedCallerApiTest` (app-runtime) | Real bearer tokens against the fake issuer: the bound policy sees the token's subject, kind and scopes for read and verify; a denied caller is 403; expired, forged, wrong-audience and delegated tokens are 401 and never reach the policy; request headers cannot change the identity |
| `AuditApiTest` (app-runtime) | REST behaviour, 400 and 404 problems, verification endpoint, configured redaction rule end to end, scheduled job runs; dev-open does not remove authentication |

These tests are plain JUnit or `@QuarkusTest` classes. None of them is annotated with `@Acceptance`: no test in [test-cases.md](test-cases.md) describes this package's behaviour in full (T04, T31 and T34 depend on other packages).

## 9a. Events of the operations API (WP-105d)

Each attempt on the operations API (`docs/lifecycle.md`, section 11) writes these events, with the operator as actor:

| Action | Written | Attributes beyond the stamps |
|---|---|---|
| `policy.decision` (resource type `policy-resource`) | Once per policy decision, by the PEP (section 3, WP-103) | `policy.outcome`, `policy.decidedBy`, `policy.id`, `policy.version`, `policy.action`, `policy.recoveryPath` |
| `operation.attempt` (resource type `instance`) | Before any change; if it cannot be written, the call is refused | `oa.tenant`, `oa.instance`, `oa.operation`, `oa.outcome` (`allowed`, `denied` or `error`) |
| `operation.result` (resource type `instance`) | After the call | the same, with `oa.outcome` set to the result label |

The operation events carry the version stamps of their decision (`stamp.policy`, `stamp.policy-set`). The attributes are inline, not by reference, so the outcome is readable without the payload store.

## 10. Gaps and what remains

| Item | Owner | Note |
|---|---|---|
| External anchor of the chain head (write-once storage, periodic export of the last hash per chain) | Out of scope for WP-111; list it in the operations work (WP-401) | Without it, an owner who rewrites events and head records together is not detected |
| Verified caller identity for audit access | WP-102 (done in this change, against the fake issuer) | The caller is the verified token subject and scopes (section 7). Real-Keycloak token layout is the open WP-102 gate in `docs/identity.md` section 9 |
| Policy decision point bound for the audit API | WP-103 | The running application binds no `PolicyDecisionPoint` for audit, so in production the audit API serves nobody until a policy is bound; only the dev flag opens it. WP-103 adds the audit rules and routes the decision through the PEP, which records `policy.decision` events and bounds concurrency. `AuditAccess` asks the port directly today |
| Tenant filtering of audit reads | WP-404 | The trail is global. A caller's tenant is established but not yet applied to queries |
| Recording effects and reconciler actions | WP-108, WP-105 | Use `AuditRecorder` or `AuditSink` |
| Acceptance tests T04, T31, T34 | WP-301, WP-501, WP-203 | Not owned here |
| Head log growth (one row per event) and archival of old chains | Operations (WP-401) | Verification reads the whole chain in memory; large chains need paged verification |
| Separate audit data source and the deployment roles of section 8 | WP-304 | Documented and proven on a test role; not yet wired in `app-runtime` |
| Contract change: first-class envelope and stamp fields in `AuditEvent` | Owner decision (WP-101 contract) | Today they are reserved attribute keys; this keeps the contract unchanged |
