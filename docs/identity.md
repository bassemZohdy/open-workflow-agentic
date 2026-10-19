# Identity and delegated authority

Work package WP-102, requirement M11, acceptance test T07. Decision record: [ADR 0021](adr/0021-identity-delegation-policy-engine-and-secrets.md).
Code: `oa-runtime/src/main/java/io/github/bassemzohdy/openworkflow/agentic/runtime/identity`.

Tags used below follow ADR 0021: **[verified]** was run and passed here, **[fake]** was run only against an in-process
stand-in, **[unverified]** was not run.

## 4. Token exchange (RFC 8693)

`TokenExchanger` is the port; `Rfc8693TokenExchanger` is the client. It turns an active delegation grant into one token
for one audience.

What goes out: `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, the user's token as `subject_token`, the
agent's own workload token as `actor_token`, one `audience`, the `scope` asked for, client authentication with HTTP Basic.
The client secret is read from the `SecretProvider` on every call, so a rotated secret is used at once.

What the client refuses, because the authorization server cannot know it (Keycloak does not know our grant expiry):

| Rule | Where it is checked |
|---|---|
| The grant, and every grant above it, is active now | before the request; nothing is sent otherwise |
| The scopes asked are all held by the grant | before the request |
| No `refresh_token` in the answer | on the answer |
| The token verifies (signature, issuer, time), for exactly the audience asked and no other | on the answer |
| Subject is the grant's user, tenant is the grant's tenant, nearest actor is the grant's agent | on the answer |
| Token scopes are a subset of the scopes asked | on the answer |
| Lifetime at most 300 s, and not beyond the grant's expiry | on the answer |
| The token is not the subject or actor token handed back | on the answer |

Any failure discards the token. The audit event `identity.token.exchanged` is written before the token is returned, and
if it cannot be written the token is discarded. The token itself is held in memory only (`IssuedToken`, a `Credential`).

Consequence worth knowing: an authorization server cannot issue a token shorter than its default, so in the last 300 s of
a grant the server's token would outlive the grant and is discarded. A grant is usable for exchange until its expiry minus
the server's token lifetime, unless the server shortens the token to the subject token's expiry (the test server does; whether Keycloak does is
**[unverified]**, section 9). Operators who need the full grant life should set the server's exchanged-token lifetime lower
than 300 s.

`ResourceTokenGuard` is what a tool or resource runs on the token it receives: audience exactly its own, a delegated token
(has an actor), an id and an issue time, issued for at most 300 s, the scopes the call needs, optionally an active grant
for (tenant, user, actor) that covers them, and a bounded number of uses (default 1) counted in `ReplayCache`
(`JdbcReplayCache` in table `identity.token_use`, or `InMemoryReplayCache`). The user's own token is refused at the
resource, so a runtime that passes it through fails there.

Status: client and guard **[fake]**, against `DelegationAuthorizationServer` in oa-testkit test scope. Against Keycloak
**[unverified]**.

## 5. Re-authorization after long waits

An instance can run under a delegation grant: `POST /instances` takes an optional `delegationGrantId`. The grant must be
active now, in the caller's tenant, and given by the caller (another user's grant is answered like an unknown one, 404).
The instance then has a row in `identity.instance_authority`, written in the same transaction as the instance row
(`InstanceStore.insertIfAbsent` with an `InsertHook`): subject, grant id, correlation id, and a state. Identifiers only.

Before a restored instance runs again, the reconciler asks the `ResumeGate` (`InstanceAuthorities` implements it):

| Situation | Typed outcome | What happens |
|---|---|---|
| no authority row, or no grant | `PROCEED` | resumes as before |
| grant and all grants above it active | `PROCEED` | resumes (resume epoch + 1, `startRestored`) |
| grant revoked, expired, or an ancestor ended | `REAUTHORIZATION_REQUIRED` | not resumed; see below |
| state already `REAUTHORIZATION_REQUIRED` | `REAUTHORIZATION_REQUIRED` | stays held until the user renews |
| the check fails (database down, audit down) | `UNAVAILABLE` | not resumed, nothing marked, next pass asks again |

On `REAUTHORIZATION_REQUIRED`: the audit event `identity.reauthorization.required` is written first (grant id and reason, no
token), then the authority state is set. The registry row moves to `WAITING` with the history reason
`reauthorization.required` and the usual wait deadline (`agentic.reconciler.wait-deadline`), so an instance that nobody renews
is cancelled by the deadline repair like any other wait. This uses the existing states: RUNNING to WAITING is already a legal
transition, and a row that already waits gets a same-status history row. No state-machine change. The reason reaches the
outbox, the task view (`lastReason`) and the inspect output, which also has an `authority` section (subject, grant, correlation
id, state, reason, since).

Renewal: `POST /instances/{id}/reauthorize` with `{"delegationGrantId": "..."}`. Only the user who started the instance
(403 for another user in the tenant, 404 for another tenant), only while the instance waits (409 otherwise), only with an
active grant of the same user to the same agent (409 if it is not usable). The audit event `identity.reauthorization.granted`
is written, the grant is rebound by compare-and-set, and the reconciler resumes the instance at once. A gate that is
`UNAVAILABLE` never blocks forever and never lets an instance through.

The same check is available to a step that is about to use the grant (`InstanceAuthorities.recheck`), and a refused token
exchange (`ExchangeException.Code.GRANT_INACTIVE`) is the same condition. Wiring a live step to it belongs to the agent bridge
(WP-2xx); in WP-102 the wired point is the resume of a restored instance.

Status: **[verified]** against PostgreSQL and the real reconciler with a fake engine (`ReauthorizationResumeTest`,
`InstanceAuthoritiesTest`, `InstanceAuthorityApiTest`). With the real engine and a killed process: `IdentityRecoveryIT`
(section 7).

## 6. Secrets

`SecretProvider` is the port; `FileSecretProvider` reads files from `agentic.secrets.dir` (a mounted Kubernetes Secret, for
example). Vault is out of scope.

- Rotating values are files, never environment variables (an environment variable cannot change in a running process).
- A secret name is a plain file name. The file must resolve inside the directory; a symlink out of it is refused. The
  symlink swap Kubernetes performs inside a mount is a normal rotation.
- A changed file (real path, modification time or size) is read on the next call. An unchanged file is read again every
  `agentic.secrets.ttl` (default 30 s).
- A deleted file revokes the secret at once, even inside the ttl.
- A read error that is not "missing" lets the cached value be used for `agentic.secrets.max-stale` (default 2 min) after the
  ttl, and then the secret is unavailable. An empty, oversized or non-text file is unavailable at once and never falls back
  to an older value.
- Unavailable means `SecretUnavailableException`. The token exchanger turns it into `ExchangeException(UNAVAILABLE)` and
  sends nothing, so dependent work pauses or fails; it does not run without the secret.
- A value is never logged, never in an exception message, never in an audit attribute. Audit events
  `identity.secret.rotated` and `identity.secret.unavailable` carry the secret's name and a generation number (or a reason)
  only; there is no hash of the value.
- The JVM cannot wipe a `String`. `Credential` limits where a value travels (redacted `toString` and JSON, identity
  equality); it does not erase memory.

Status: **[verified]** with a real directory (symlinks, rotation, deletion, unreadable path) in
`FileSecretProviderTest`, and with the exchanger in `SecretRotationExchangeTest` against the in-process authorization
server. Behaviour on a real Kubernetes mount is **[unverified]**: the atomic-swap layout is reproduced with symlinks, not
with a kubelet.

## 7. Propagation and no persistence

**What is carried.** Three identifiers: the subject, the grant id and the correlation id. `AuthorityScope` holds them for the
current thread and in the log context (`oa.subject`, `oa.grant`, `oa.instance`). `wrap`, `wrapCall`, `wrapGet` and
`wrapExecutor` capture the scope when a task is created and install it, then remove it, around the task on whatever thread
runs it. The scope is context, not authentication: nothing trusts it for an access decision. Decisions use the registry row
and the grant store.

**What is stored.** Only identifiers, in `identity.instance_authority` (section 5), written in the transaction that creates the
instance. Nothing about identity travels in workflow state, the engine's checkpoints, outbox payloads or audit events beyond
those identifiers. A token is never stored. The table refuses values that look like one (length, character set, a value
starting `eyJ`).

**Resume.** After a restart the engine instance has no thread context. `InstanceAuthorities.restore(engineInstanceId)` reads the
registry row and installs the scope again, so a step on a restored instance sees the original subject, grant and correlation id.
A token for a downstream call is obtained again by exchange (section 4) under that grant; the old one was never kept.

**Evidence.** `IdentityRecoveryIT` (failsafe, real child JVMs, SIGKILL, real engine, tokens of the fake issuer):

1. Two instances start under two grants of one user with distinct correlation ids; a secret is read and rotated in the process.
2. The process is killed with SIGKILL; one grant is revoked while it is down.
3. After restart the instance with the valid grant resumes (epoch 1) and its step sees the same subject, grant and correlation id.
   The other one is held: no effect call, status WAITING, history reason `reauthorization.required` once, one audit event,
   inspect shows the state, the task view shows the reason.
4. The user renews with a new grant; the held instance resumes under the new grant with the original subject and correlation id.
5. Both complete. Every table of every non-system schema (workflow rows, registry, history, outbox, task view, audit events
   and payloads, scheduler rows, grants, authority) and both process logs are searched for the tokens used (whole, payload part,
   signature part, hex, base64) and for both secret values with their hex and base64 forms. The search first has to find a token
   planted in a table, so it cannot pass by looking at nothing. No hit is allowed.

Limits: the scan proves the absence of these exact strings and encodings, not of every transformation. Checkpoints are the
engine's persistence tables in the same database; the scan covers them as part of "every table". The database password is not
scanned (it appears in connection settings by design). The live in-process path is not gated by a grant check; the check is
available to steps (`InstanceAuthorities.recheck`) and is wired at resume. Verified against the testkit's fake issuer only.

## 9. Keycloak 26.2+ integration test (unverified)

This test has not been run. No container runtime is available where WP-102 was built, so no Keycloak was started. It is
described exactly so that someone with Docker can run it, and ADR 0021 requires it before WP-102 is called closed.

Set-up (Keycloak 26.2 or later; the realm name below is `agentic`):

1. Start Keycloak: `docker run --rm -p 8080:8080 -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin quay.io/keycloak/keycloak:26.2 start-dev`.
2. Create realm `agentic`. Add a user `alice` with a password. Give the realm a custom user attribute `tenant` and a
   protocol mapper that writes it as the token claim `tenant`. Add a client scope mapper that writes the user's scopes into
   the space-separated claim `scope` (the default).
3. Create client scopes `cases:read` and `cases:write`, and optional client scopes for the audiences.
4. Create client `oa-api` (the API; public or confidential, direct access grants enabled for the test) and give it an audience
   mapper so a user token names `oa-api`.
5. Create client `oa-agent-runtime`: confidential, service accounts enabled, **Standard token exchange** enabled (Advanced
   settings, "Standard token exchange"). Its secret goes into the file named by `agentic.identity.exchange.client-secret-name`.
6. Create clients `tool-cases` and `tool-search` as the exchange targets (the audiences). In the exchanged token the
   `aud` must be exactly the target.
7. Give the service account of `oa-agent-runtime` a `sub_kind=AGENT` and a `tenant` claim through hardcoded-claim mappers
   (the agent's workload identity).

Configuration of the runtime for the test:

```
agentic.identity.issuer=http://localhost:8080/realms/agentic
agentic.identity.jwks-uri=http://localhost:8080/realms/agentic/protocol/openid-connect/certs
agentic.identity.audience=oa-api
agentic.identity.token-endpoint=http://localhost:8080/realms/agentic/protocol/openid-connect/token
agentic.identity.exchange.client-id=oa-agent-runtime
agentic.identity.exchange.client-secret-name=exchange-client-secret
agentic.secrets.dir=/tmp/agentic-secrets
```

Steps and what must hold:

1. Get a token for `alice` with `scope=cases:read cases:write` (password grant against `oa-api`). Get the workload token of
   `oa-agent-runtime` (client credentials).
2. Create a grant alice -> `oa-agent-runtime` for those scopes in our store.
3. Exchange for audience `tool-cases` and scope `cases:read`. Expect: success, `aud` exactly `tool-cases`, scope exactly
   `cases:read`, a lifetime of at most 300 s, no `refresh_token`.
4. Ask for scope `cases:delete` that alice's token lacks. Expect an `invalid_scope` refusal.
5. Ask for an audience that is not a registered target. Expect an `invalid_target` refusal.
6. Exchange when the user's token has expired. Expect `invalid_grant`.
7. Find out three things this implementation assumes and cannot know without Keycloak, and record the answer in this file:
   a. Does the standard exchange accept `actor_token` at all? ADR 0021 reads that delegation (`act`, `may_act`) is a
      preview feature. If Keycloak ignores or rejects `actor_token`, `Rfc8693TokenExchanger` refuses every token because
      the answer carries no actor (it checks for the grant's agent as nearest actor), and the actor would have to come from
      a protocol mapper or a different design. This is the most likely place for the integration to differ.
   b. Does the exchanged token carry the `tenant` claim and the `scope` claim in the layout `JwtTokenVerifier` reads?
   c. Is the exchanged token's lifetime at most 300 s, and does it end with the user's token if that is sooner?
8. Revoke the grant in our store and exchange again. Expect refusal by our client before any request (this part does not
   depend on Keycloak).

Run it with the runtime's `TokenExchanger` pointed at the real endpoint; a JUnit test can use `Rfc8693TokenExchanger`
directly, with a `SecretProvider` that returns the client secret. Until this has run, every statement about Keycloak in this
file is **[unverified]**.
