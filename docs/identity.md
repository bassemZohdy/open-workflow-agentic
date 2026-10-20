# Identity and delegated authority

Work package WP-102, requirement M11, acceptance test T07. Decision record: [ADR 0021](adr/0021-identity-delegation-policy-engine-and-secrets.md).
Code: `modules/oa-runtime/src/main/java/io/github/bassemzohdy/openworkflow/agentic/runtime/identity`.

Tags used below follow ADR 0021: **[verified]** was run and passed here, **[fake]** was run only against an in-process
stand-in, **[unverified]** was not run.

## 1. Model

Three kinds of subject act in the platform: a **user** (a person, authenticated by the identity provider), an **agent**
(a workload with its own identity), and the **runtime** itself. Authority always starts with a user. It reaches an agent only
through a **delegation grant** that the user gave. It reaches a tool or another system only through a **short-lived token**
that the identity provider issued for exactly that audience, after the platform checked the grant.

| Piece | Where | Kept |
|---|---|---|
| Verified caller (who is calling the API) | `CallerContextResolver`, `JwtTokenVerifier` | nothing; verified per request |
| Delegation grant (what a user lets an agent do, and for how long) | `DelegationGrants`, `identity.delegation_grant` | PostgreSQL, append-only history |
| Token exchange (a token for one tool, one audience) | `Rfc8693TokenExchanger` | nothing; the token is returned and used |
| Resource guard (what a tool accepts) | `ResourceTokenGuard`, `ReplayCache` | `identity.token_use` (token id and expiry only) |
| Instance authority (who an instance runs for) | `InstanceAuthorities`, `identity.instance_authority` | identifiers only |
| Secrets (client secret of the exchange) | `SecretProvider`, `FileSecretProvider` | files mounted by the platform |

There is no token passthrough. A token the user presented to the API is never forwarded to a tool: the API's audience is
`agentic.identity.audience`, a tool's token is exchanged for the tool's audience, and the resource guard refuses a token whose
audience is not exactly its own.

## 2. Verified caller

`CallerContextResolver` builds the caller from a bearer token. `JwtTokenVerifier` checks the signature (keys from
`agentic.identity.jwks-uri`), the issuer, the audience, expiry and not-before with the configured clock skew, and requires
`sub`, `exp` and the tenant claim. The scopes are the `scope` claim; a scope that does not have the policy syntax is dropped,
which can only narrow authority. A token with an `act` claim (a delegated token) is refused at the API: delegated tokens are
for tools, not for this API. `X-Tenant`, `X-Subject` and `X-Scopes` are ignored on this path.

The **development header seam** (`agentic.dev.trust-caller-headers=true`) is honoured only when the active profile is in
`agentic.dev.header-seam-profiles` (default `dev,test`). In any other profile the application refuses to start with the flag
set. `HeaderSeamProductionIT` runs the packaged jar to show it. Without issuer settings, every bearer token is refused.

## 3. Delegation grants

A root grant is given by the user to one agent: tenant, user, agent, a set of scopes, and an expiry (at most
`agentic.identity.grant-max-lifetime`, default 30 days). An agent that holds a grant can delegate a part of it to another
agent: a **child grant** with a subset of the scopes, an expiry no later than the parent's, a new agent that is not already in
the chain, and at most `DelegationGrant.MAX_DEPTH` (8) levels. Widening is refused by the service
(`PermissionWideningException`) and again by a database trigger, so code that bypasses the service cannot widen either.
Grants are never edited: they are revoked (the subtree goes with the root) or they expire. Creation, revocation and expiry
write audit events. Creation is fail-closed (a grant whose event cannot be written is revoked again); revocation stays in
force even if its event cannot be written, and the failure is logged at ERROR.

## 4. Token exchange (RFC 8693)

`TokenExchanger` is the port; `Rfc8693TokenExchanger` is the client. It turns an active delegation grant into one token
for one audience.

What goes out: `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, the user's token as `subject_token`, the
agent's own workload token as `actor_token`, one `audience`, the `scope` asked for, client authentication with HTTP Basic.
The client secret is read from the `SecretProvider` on every call, so a rotated secret is used at once.

What the client refuses, because the authorization server cannot know it (it does not know our grants, and it takes the
scopes and the lifetime of the new token from its own configuration, not from the user's token; section 9.3). ADR 0021 H1
lists the checks; they are all in force:

| Rule | Where it is checked |
|---|---|
| The grant, and every grant above it, is active now | before the request; nothing is sent otherwise |
| The scopes asked are all held by the grant **and** by the verified subject token | before the request |
| Both input tokens verify (signature, issuer, time) with the exchanging client as audience; the subject token is the grant's user in the grant's tenant and carries exactly the delegation chain of the grant's parent (none for a root grant); the actor token is the grant's own, undelegated workload token; neither is expired | before the request |
| No `refresh_token` in the answer; token type Bearer | on the answer |
| The token verifies (signature, issuer, time), for exactly the audience asked and no other, with a `jti` and an issue time | on the answer |
| Subject is the grant's user, tenant is the grant's tenant, the actors are exactly the grant's chain, nearest first (a missing or different chain is refused, never flattened) | on the answer |
| Token scopes are a non-empty subset of the scopes asked | on the answer |
| The signed `exp` is at most 300 s away and not beyond the grant's expiry **or the verified subject token's expiry**. An overlong bearer is refused; only the signed `exp` counts, never a shortened Java-side expiry | on the answer |
| The token is not the subject or actor token handed back | on the answer |
| The grant is active and still covers the scopes **after** the answer | after the answer, before the token is returned |

Any failure discards the token. The audit event `identity.token.exchanged` is written before the token is returned, and
if it cannot be written the token is discarded. The token itself is held in memory only (`IssuedToken`, a `Credential`).
Every non-success HTTP status is a failure whatever the OAuth error name says (`REFUSED` for 400/401/403, `UNAVAILABLE`
otherwise, which a caller may retry); the `oauthError` is diagnostic only.

Consequence worth knowing: an authorization server cannot issue a token shorter than its client's configured lifetime, so in
the last 300 s of a grant, and in the last 300 s of the user's own token, the server's token would outlive its bound and is
discarded: such an exchange fails and needs re-authorization. Keycloak does not shorten to the subject token (a 100 s subject
token gave a 300 s exchanged token; section 9.3). Operators who need the full life should set the exchanging client's
access-token lifespan below 300 s.

`ResourceTokenGuard` is what a tool or resource runs on the token it receives: audience exactly its own, a delegated token
(has an actor), an id and an issue time, issued for at most 300 s, the scopes the call needs, optionally an active grant
for (tenant, user, actor) that covers them, and a bounded number of uses (default 1) counted in `ReplayCache`
(`JdbcReplayCache` in table `identity.token_use`, or `InMemoryReplayCache`). The user's own token is refused at the
resource, so a runtime that passes it through fails there.

Status: client and guard run **[verified]** against real Keycloak 26.2.5 (standard exchange: the answer has no `act`, so
the client refuses it) and 26.8.1 (native delegation preview: the client issues a delegated token), by `KeycloakStandardIT` and
`KeycloakDelegationIT` (section 9.4), and **[fake]** in the in-process unit tests against `DelegationAuthorizationServer` in oa-testkit.
The real-provider gate is not closed until those tests pass in CI (section 9.4) and the review of the exact head is recorded.

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

## 8. Threat notes

- **No passthrough.** The API refuses delegated tokens, tools refuse the user's token (audience), and the exchange is the only
  way from one to the other. The exchanger refuses to send a refresh token, and refuses an issued token equal to its input.
- **Audience binding.** The exchanger asks for one audience and verifies that the issued token names exactly that audience;
  the resource guard requires the audience set to equal its own name.
- **Lifetime.** A delegated token lives at most 300 seconds: the exchanger and the guard both refuse longer ones. The guard
  also refuses a token with no `jti` or `iat`.
- **Replay.** Each token id is accepted `max-uses` times (default once). The in-memory cache is bounded and refuses new tokens
  when full; the JDBC cache is shared by all processes and survives a restart. A failing cache refuses the token. The replay
  count is taken last, so a token refused for another reason does not use up its single use.
- **Clock skew.** `agentic.identity.clock-skew` (default 30 s, at most 5 minutes) is accepted on `exp` and `nbf`. A token can
  therefore be used up to the skew after its expiry. Keep the skew small and the clocks synchronised.
- **Revocation latency.** A revoked grant stops new exchanges at once and stops tokens at the guard on the next call (the guard
  looks the grant up). A resource server that does not use the guard keeps accepting a token until it expires, at most 300 s plus
  the skew. Revocation of the user's session at the identity provider is not seen by the platform.
- **The last five minutes of a grant.** The identity provider cannot know the grant's expiry, so the exchanger discards any
  issued token that would outlive the grant. In the last 300 seconds of a grant, exchanges therefore fail (section 4).
- **Stored state.** Only identifiers are stored (section 7). The database refuses values that look like a token.
- **Audit.** Every exchange is audited before the token is returned; if the audit fails, the token is discarded. Refusals are
  audited on a best-effort basis. Events carry names, ids and reasons, never a token or a secret.

## 9. Keycloak integration: what a real server does (WP-102b)

Run on 2026-10-19 and 2026-10-20 against real Keycloak distributions (no container runtime in the authoring environment, so
the tarballs were started with `kc.sh start-dev`): **26.2.5** and **26.8.1**, the latter with
`--features=token-exchange-delegation,parameterized-scopes`. CI runs the same tests on the pinned images
`quay.io/keycloak/keycloak:26.2.5` and `:26.8.1` (workflow `keycloak-interop`, PR #40). ADR 0021's H1 amendment decides what
the product requires: native, issuer-signed actor proof; no actorless downgrade; no `may_act` hard-coded in a mapper.

### 9.1 Realm set-up that the old text left out (all needed, all found by failing)

1. `tenant` and `sub_kind` must be declared in the realm's **user profile**, or Keycloak silently drops the attribute.
2. The exchanging client (`oa-agent-runtime`) must appear in the **audience of the subject token** and in the audience of its
   own workload token: the product verifies both input tokens for that audience. Standard exchange needs the client attribute
   `standard.token.exchange.enabled=true`; delegation gets the actor client into `aud` by itself.
3. The audiences an exchange may name come from the user's **client roles on the target client**. Without a role on
   `tool-cases` the exchange is refused.
4. `profile` and `email` are default scopes of every client and leak into exchanged tokens unless removed from the agent client.
5. Token lifetime is the client's, not ours: the realm default was 3600 s. A client attribute `access.token.lifespan=300`
   on the exchanging client gives 300 s. Lifetime is **not** limited by the subject token (see 9.3).
6. Do **not** use hard-coded `tenant`/`sub_kind` mappers on the agent client. They also stamp user-subject exchanged tokens
   (alice's exchanged token became `sub_kind=AGENT`). Use user-attribute mappers and put the attributes on the agent's
   service-account user; then alice's token keeps `tenant-1` and no `sub_kind`.
7. **Native delegation needs three things, none of them a token mapper:** the realm's *Admin Permissions* (FGAP v2) enabled with
   a `delegate` permission on the **Users** resource type for the user (here alice), whose policy is the *client* policy of the
   agent runtime; a client for the login with *Consent required* on and the standard flow; and the user's consent on the screen
   ("Allow oa-agent-runtime to act on your behalf?"), asked at every login. The login asks for the scope
   `delegation:client:oa-agent-runtime`. Keycloak then writes `may_act: {sub: <service-account user id>, client_id}` into
   the user's token itself.

### 9.2 Results

| Question | 26.2.5 (standard exchange) | 26.8.1 with delegation features |
|---|---|---|
| Exchange for `aud=tool-cases`, `scope=cases:read` | 200; `aud` exactly `tool-cases`; `scope` exactly `cases:read`; `sub` = alice; `tenant` kept; no `refresh_token` | same, from a subject token that has `may_act` |
| `actor_token` | **ignored**: garbage and the user's own token still return 200 | validated: garbage, the user's own token, and another service account's token are `400 invalid_request` |
| `act` claim in the result | **never present** | present: `act.sub` = **Keycloak user id of the agent's service account**, not the client id |
| What authorises the actor | nothing | `may_act` in the user's token, issued only after the FGAP permission **and** the user's consent |
| User refuses consent | n/a | no code is issued: the redirect carries `error=access_denied` |
| No `delegate` permission for the actor | n/a | the delegation scope is silently dropped; the login works and the token has no `may_act`; an exchange with an actor token is `400` |
| Exchange **without** `actor_token` | 200 | `400` when the subject token carries `may_act` |
| A scope the user's token does not hold (`cases:delete`) but the client may use | **200, the scope is granted** | **200, the scope is granted** |
| Unregistered audience | refused: **`invalid_client`** ("Audience not found"), not `invalid_target` | refused |
| Subject token already expired / session logged out | refused (`invalid_request`, "Invalid token") | refused |
| Lifetime of the exchanged token | 300 s (client attribute); **not** bounded by the subject token | 300 s; **not** bounded by the subject token |
| Signing-key rotation | n/a | a token of the new key is not accepted by a verifier until it has refetched the key set (rate limited to 30 s): refused fail-closed meanwhile |

### 9.3 What this means for the design

* **ADR 0021 as first written does not hold on a released stable feature.** Standard exchange (26.2) records no actor, so
  the product refuses every such token (`actorlessExchangeIsRejected`). Delegation exists only behind preview feature flags
  and works differently from the first ADR text: the **user's** token carries `may_act`, `act.sub` is a user id, and the user's
  consent is a live screen. The ADR's H1 amendment adopts strict native delegation as an explicit preview profile.
* **Keycloak does not enforce three bounds that the product therefore enforces itself** (each is asserted in the tests, the
  server's behaviour included, so a Keycloak change shows up as a failing test):
  1. *Scope.* The exchanged token's scopes come from the exchanging client's configuration, not from the user's token: a user
     who never held `cases:delete` still gets it in the exchanged token if the client may use it. The product refuses a request
     for a scope the verified subject token does not hold.
  2. *Subject expiry.* A 100 s subject token produced a 300 s credential. The product refuses any signed `exp` past the verified
     subject expiry. The consequence is deliberate: a near-expiry subject token needs re-authorization.
  3. *Grant expiry and revocation.* The server does not know our grants. Expiry is bounded as above; revocation is checked before
     the request, after the answer, and at every resource.
* **Revocation granularity (residual limitation).** An exchanged token does not name the grant it was issued under, so a
  resource asks whether *any* active grant of this user to this actor covers the scopes. Revoking one of several equivalent
  grants does not stop a token issued under it; ending every grant of the pair does. Carrying a grant reference in the token
  would need an issuer claim that Keycloak cannot compute.
* Refusal is decided on the HTTP status, not on OAuth error names (`invalid_client` and `invalid_request` stand where this file
  once said `invalid_target` and `invalid_grant`).

### 9.4 The Keycloak integration tests (the real-provider gate)

`KeycloakStandardIT` (profile `standard`, Keycloak 26.2.5) and `KeycloakDelegationIT` (profile `delegation`, Keycloak 26.8.1
with the two features), in `apps/app-runtime/src/test/java/.../identity/`, run the product's own `JwtTokenVerifier`,
`Rfc8693TokenExchanger`, `DelegationGrants` (PostgreSQL) and `ResourceTokenGuard` against a real server. `KeycloakRealm` builds
a **uniquely named disposable realm** per class through the admin API and deletes it afterwards; `KeycloakHarness` wires the
product components; `KeycloakSupport` reads the settings. With `-Dkeycloak.required=true` a missing server, an unreachable
server or a server of the wrong profile is a **failure**, never a skip; without it the classes skip, so a developer build
needs no Keycloak.

| Class | Required cases (the CI contract of PR #40) |
|---|---|
| `KeycloakStandardIT` | `realIssuerSignatureAndAudienceAreVerified`, `actorlessExchangeIsRejected`; extra: `theServerStillRefusesWhatItCanSee` |
| `KeycloakDelegationIT` | `nativeDelegationPreservesAuthorityBounds`, `invalidActorIsRejected`, `revokedOrExpiredGrantIsRejected`, `subjectExpiryBoundsIssuedCredential`, `issuerKeyRotationIsHandled`, `delegationRequiresPermissionAndConsent` |

Run locally against a throw-away server (start it with `bin/kc.sh start-dev --http-port=18080` and the bootstrap admin
`KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD`; for delegation add
`--features=token-exchange-delegation,parameterized-scopes`; the database is the usual `DB_URL`):

    export KEYCLOAK_URL=http://127.0.0.1:18080 KEYCLOAK_PROFILE=delegation \
           KEYCLOAK_ADMIN_USER=admin KEYCLOAK_ADMIN_PASSWORD=<bootstrap password>
    mvn -B -pl apps/app-runtime -am verify -Dit.test=KeycloakDelegationIT \
        -Dfailsafe.failIfNoSpecifiedTests=false -Dkeycloak.required=true \
        -Dkeycloak.profile=delegation -Dkeycloak.url="$KEYCLOAK_URL"
    python3 tools/require_keycloak_evidence.py --profile delegation

(profile `standard` and `KeycloakStandardIT` for 26.2.5). `tools/require_keycloak_evidence.py` is the GPT-owned checker of
PR #40: it rejects missing, failed, errored and skipped cases.

What they establish, with the product code in the loop: the verifier reads tenant, scopes and agent kind from real tokens and
refuses a wrong audience, a changed signature, another realm's token and an expired token; the exchanger refuses the actorless
standard-exchange answer; with real permission and consent it issues one delegated token for one audience whose `act` is the
agent's service-account user, bounded by the user's scopes, the subject expiry and the grant; it refuses another agent's
workload token, a garbage actor, another tenant, a widened scope, a revoked or expired grant (also one revoked while the request
is in flight) and an overlong bearer; the resource refuses a replay, another resource's token and a token whose grant was
ended; a verifier survives rotation fail-closed and an unreachable key set never accepts; and consent refusal, a missing
permission and a password login never produce `may_act`. Mutation checks: removing the subject-expiry bound, the post-answer
grant check, the actor identity check or the subject scope bound each makes the matching delegation case fail.

### 9.5 Still unverified

Keycloak 26.8 in a stable (non-preview) configuration; any other identity provider; multi-hop chains through a real provider
(a chain the provider cannot preserve is refused, never flattened, and the in-process tests cover the exact-chain rule);
an exchanged token that names its grant.

### 9.6 Reproduction recipe

Start Keycloak 26.2.5 (or 26.8.1) as in 9.4. Use the admin REST API (`/admin/realms`) with an admin-cli password-grant token.
For delegation create the realm with `adminPermissionsEnabled=true`. Then, in this order:

1. Realm with access token lifespan 3600 s. Add user-profile attributes `tenant` and `sub_kind`.
2. Client scopes `cases:read`, `cases:write`, `cases:delete` (include in token scope; hide from consent). Never offer
   `cases:delete` to a user client.
3. Clients `oa-api` (public, direct access grants) and, for delegation, `oa-console` (public, *consent required*, standard flow,
   redirect `http://127.0.0.1:9/callback`) and a copy with `access.token.lifespan=100`. Mappers: audience `oa-agent-runtime`,
   user-attribute `tenant` and `sub_kind`. Optional scopes `cases:read`, `cases:write`.
4. Clients `oa-agent-runtime` and `oa-other-agent`: confidential, service accounts on, standard flow off, attribute
   `standard.token.exchange.enabled=true`, `access.token.lifespan=300`; remove default scopes `profile` and `email`. Mappers:
   audience of the runtime, user-attribute `tenant` and `sub_kind`. Optional case scopes. On each service-account user set
   `tenant=tenant-agent` and `sub_kind=AGENT`.
5. Confidential clients `tool-cases` and `tool-search` with a client role `invoke` each.
6. User `alice` (password, attribute `tenant=tenant-1`) with the `invoke` role of both tool clients.
7. Delegation only: in the `admin-permissions` client's authorization settings, a *client* policy naming `oa-agent-runtime`, and
   a scope permission on the resource type `Users` for the resource alice, scope `delegate`, with that policy. The other agent
   gets no permission, on purpose.

Requests (all to the realm token endpoint): alice's token by password grant against `oa-api`; for delegation, alice's token by
the browser flow against `oa-console` with `scope=openid cases:read cases:write delegation:client:oa-agent-runtime` (login form,
then the consent screen, then the authorization code); the workload token by client credentials; then a
`urn:ietf:params:oauth:grant-type:token-exchange` request authenticated as `oa-agent-runtime` (HTTP Basic) with `subject_token`,
`audience`, `scope` and `actor_token`. The results are in 9.2; the assertion-bearing form of this recipe is the integration tests of 9.4.

## 10. Configuration

| Property | Default | Meaning |
|---|---|---|
| `agentic.identity.issuer` | none | accepted `iss`; without it every bearer token is refused |
| `agentic.identity.jwks-uri` | none | key set of the issuer; https, or http on loopback |
| `agentic.identity.audience` | none | the audience of this API |
| `agentic.identity.tenant-claim` | `tenant` | claim holding the tenant; a user token without it is refused |
| `agentic.identity.subject-kind-claim` | `sub_kind` | claim holding the subject kind; absent means USER |
| `agentic.identity.clock-skew` | `PT30S` | at most 5 minutes |
| `agentic.identity.jwks-timeout` | `PT5S` | connect and read timeout of the key fetch |
| `agentic.identity.grant-max-lifetime` | `P30D` | longest grant |
| `agentic.identity.expiry-sweep-cron` | `0 */5 * * * ?` | sweep that marks expired grants |
| `agentic.identity.token-endpoint` | none | RFC 8693 endpoint; https or loopback http. Without it, exchange is refused |
| `agentic.identity.exchange.client-id` | none | client of the agent runtime |
| `agentic.identity.exchange.client-secret-name` | none | file name in the secrets directory |
| `agentic.identity.exchange.timeout` | `PT5S` | one exchange request |
| `agentic.secrets.dir` | none | directory of secret files |
| `agentic.secrets.ttl` | `PT30S` | an unchanged file is re-read at least this often |
| `agentic.secrets.max-stale` | `PT2M` | a cached secret may be used this long after the ttl while the file cannot be read |
| `agentic.dev.trust-caller-headers` | `false` | development header seam; see section 2 |
| `agentic.dev.header-seam-profiles` | `dev,test` | profiles where the seam may be on |

## 11. Operations

**Rotating the exchange client secret.** Write the new secret into the mounted file. The provider notices a changed file at
once and an unchanged one within the ttl; the next exchange uses the new value. Rotate the secret at the identity provider so
that both are valid for one overlap period, then retire the old one. A missing file revokes the secret immediately; a file
that cannot be read is served from cache for at most ttl plus max-stale, then exchanges fail. `identity.secret.rotated` and
`identity.secret.unavailable` events are written (name and generation or reason only).

**Rotating the issuer's signing keys.** Publish the new key in the JWKS before the issuer signs with it; remove the old one
after the longest token lifetime of the issuer.

**Revoking a grant.** The user, the agent that holds the grant, or its creator calls `DelegationGrants.revoke`; an operator uses
`revokeByOperator`. The subtree is revoked together. New exchanges fail at once; tokens already issued stop at the guard
(section 8). Instances that run under the grant are held at their next resume with `reauthorization.required` (section 5); the
user renews with `POST /instances/{id}/reauthorize`. There is no HTTP endpoint for grants in this work package.

**An instance held for re-authorization.** Inspect shows the `authority` section (state, reason, since). If nobody renews it,
the wait deadline (`agentic.reconciler.wait-deadline`) cancels it.

## 12. What is verified, what is a fake, what is unverified

| Statement | Status |
|---|---|
| Token verification, grants, narrowing (service and database triggers), replay cache, instance authority, resume gate, reauthorization, secret provider | **[verified]** against PostgreSQL and the real code, with tokens of the fake issuer |
| Token exchange and resource guard (in-process unit and acceptance tests) | **[fake]**: the testkit's in-process authorization server and issuer (`DelegationAuthorizationServer`, `FakeIdentityProvider`). It follows the behaviour documented for Keycloak 26.2+, but is not Keycloak |
| Kill/restart: identity restored, a held instance, no credential stored | **[verified]** with real child JVMs and the real engine (`IdentityRecoveryIT`), tokens from the fake issuer |
| T07 | `DelegatedAuthorityAcceptanceTest`: widening, expiry and replay with two scripted agents against the fake issuer; policy boundary with the embedded Cedar engine. Real multi-agent flows are WP-207 |
| A real Keycloak 26.2.5 and 26.8.1: exchange, `actor_token`, `act`/`may_act` with real permission and consent, scope/lifetime bounds, expiry, revocation, key rotation | **[verified locally]** 2026-10-20 by `KeycloakStandardIT` (3/3) and `KeycloakDelegationIT` (6/6) with the product exchanger, verifier, grants and guard (section 9.4); **CI evidence pending** (PR #40 matrix) until the exact head is green |
| A live step gated by the grant check | **not built**: only resume is wired; `InstanceAuthorities.recheck` exists for the agent bridge (WP-2xx) |
| `TokenExchanger` used by a running flow | **not built**: the bean exists, nothing in the runtime injects it yet |
| Identity carried through asynchronous tasks | the `AuthorityScope` wrappers exist and are unit tested; no runtime component uses them yet except resume |

### Statements in the ADR and the plan that the implementation shows to be wrong or unsupported

Listed, not edited, as agreed.

1. ADR 0021 says the issuer limits a token "never beyond the grant's expiry". An identity provider does not know our grants.
   The client has to discard such a token, which the exchanger does (and which is why the last 300 s of a grant fail).
2. ADR 0021 cites `TokenExchangeTest`, 11 tests, as verification. Those ran against the spike's own server, not Keycloak.
3. `docs/lifecycle.md` section 11.9 said authentication depends on WP-102 and that scopes come from headers. This work package
   updated 11.8 and 11.9.
4. `docs/policy.md` section 8 said verifying the grant is WP-102's job. It is done in the exchanger, the guard and the resume
   gate; the PEP still does not check it. Updated.
