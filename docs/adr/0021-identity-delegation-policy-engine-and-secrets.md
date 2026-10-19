# ADR-0021: Identity delegation, policy engine and secret store

**Status:** Accepted  
**Date:** 2026-10-18

Evidence: [WP-006 spike report](../spikes/WP-006-identity-policy-secrets.md) (its code was removed in issue #30 and is in git history at `b76181d`). Owner decisions D-1 and D-2 of the [plan](../plan/README.md) are the inputs; the secret store decision is D-9 in the plan, decided on 2026-10-19 (section 3 below).

Each part of the decision is marked **[verified]** (a test in the spike ran) or **[read-in-docs]** (taken from official documentation or from an artifact's own documentation, not executed). Parts marked **[unverified]** must not be relied on until the named check is done.

## Context

Requirements M11 and M12 need three things the plan leaves open:

1. A user's authority must reach an agent or a workflow as a short-lived, narrowly scoped token, never as a passed-through user token, and must be renewed after long waits (M11).
2. Policy must be enforced outside the agent at points the agent cannot bypass, must fail closed when unavailable, and decisions must be attributable and versioned (M12, M02, M26, M27).
3. Secrets must be stored, rotated, revoked and reacquired (M11, M13).

Constraints in this environment: no container runtime, so no Keycloak, Vault or Kubernetes cluster could be run. Some official documentation could not be read (see the spike report, section 4).

## Decision

### 1. Delegation: our grant, standard token exchange, short-lived tokens

- A user's request creates a **delegation grant** in our own store: subject user, agent, scopes, expiry. The grant is not a token and is not in the identity provider.
- When an agent or a workflow acts, the runtime exchanges the user's access token and its own workload token for a new token with the RFC 8693 request (`grant_type` token-exchange, `subject_token`, `actor_token`, `audience`, `scope`). The runtime verifies the issuer-signed result against the requested audience/scope and rejects any widening. The accepted JWT itself expires no later than the minimum of 300 seconds from issue, the verified subject token expiry and the active grant-chain expiry; the issuer does not know our grants.
- Agent-to-agent delegation repeats the exchange; the scope can only narrow and the chain is recorded in `act`.
- No refresh token is used. After a long wait the runtime re-checks the grant and exchanges again; if the grant has expired, the step waits for re-authorization.
- Resource servers accept a token only for their own audience. A user's gateway token is refused by tools.
- Verified: all of the above, against our in-process authorization server (`TokenExchangeTest`, 11 tests). **[verified]** for our server; the client and resource checks are ours and are what the runtime will use.
- Keycloak as issuer (D-1): supported for authentication, with delegation capability separately gated. Standard exchange without a signed `act` is not accepted for delegated effects. Native actor-bearing Keycloak delegation is a separately enabled preview integration profile, not an automatic production default. See the H1 amendment below; real product-client interoperability remains an open WP-102 gate.
- Workflow tasks: quarkus-flow-oidc 1.2.0 maps only the client-credentials and password grants **[read-in-docs, artifact inspection]**. Exchange from workflow tasks needs a custom auth provider, or a direct `quarkus-oidc-client` exchange grant (`quarkus.oidc-client.grant.type=exchange`, documented in the artifact **[read-in-docs]**). **[unverified]** the second option is not run.

### 2. Policy: a PDP port with Cedar embedded by default; OPA sidecar where OPA already runs

- All enforcement points call one `Pdp` port. Callers never depend on the engine.
- Default engine: **Cedar (cedar-java 4.10.0, `uber` classifier) embedded in the runtime**. Verified: default deny; forbid overrides permit; an evaluation error is not an allow; an empty or malformed policy set denies; an engine exception denies; a high-risk forbid works without approval (`CedarToolAuthzTest`, 11 tests). Latency: about 0.4 ms p50 and 4.5 to 6 ms p99 per decision in process (sandbox, three runs).
- Alternative engine: **OPA sidecar** (`opa` 1.13.2 `run --server`) where the organization already runs OPA. Verified: same decisions; a stopped sidecar denies; a malformed bundle does not start (`OpaSidecarTest`, 8 tests). Latency: about 0.4 ms p50 over localhost HTTP.
- OPA compiled to Wasm and run in process (`opa-java-wasm` 2.0.0 on Endive) is verified for our module (`OpaWasmEmbeddedTest`, 3 tests) at 17 to 25 µs p50. **[unverified]** Its licence, ABI compatibility with newer OPA and its maintenance status. It is not adopted until those are checked.
- Enforcement points: a Quarkus `HttpSecurityPolicy` in front of HTTP tool routes **[verified]** (`ToolCallHttpPolicyTest`, `ToolCallFailClosedTest`, `ToolCallBrokenPolicyTest`); and a call-site wrapper for in-process calls **[unverified]**, not built.
- Fail closed everywhere: no loaded policy, a policy parse error, an engine exception, an engine timeout or a non-boolean result all deny. Verified for Cedar, the OPA sidecar and the Quarkus policy. **[unverified]** for the call-site wrapper.
- Attribution: a Cedar decision names the deciding policy by an engine-assigned id (`policy0`, `policy1`), not by the `@id` annotation **[verified]**. The plan needs its own stable policy-id map. Policy-set version recording is not built **[unverified]**.
- Default deny for uncovered paths and non-bypass of in-process calls are not verified **[unverified]**: see Consequences.

### 3. Secrets (D-9, decided 2026-10-19): Vault where the organization runs it, Kubernetes Secrets for static values

- If the organization runs Vault: credentials are dynamic or leased and renewed by the application through `quarkus-vault`. **[read-in-docs, version mismatch]** The BOM pins 4.9.0; the docs read describe 5.0.0.Beta1. **[unverified]** lease semantics (the Vault lease page could not be read) and renewal failure handling. Decided 2026-10-19: `quarkus-vault` stays on the BOM's 4.9.0, and a Vault integration test on 4.9.0 is required before the secret store is chosen. Check: the Vault integration test named here.
- Kubernetes Secrets for static values only, read from mounted files and reloaded or restarted on change. **[read-in-docs, CSI driver docs]**: rotation for mounted secrets is driven by kubelet's republish cycle with a default 2-minute window; environment variables from synced Secrets are not updated without a pod restart. **[unverified]** core Kubernetes mount timing and encryption at rest.
- Application rules, regardless of store: renew or re-read before the TTL; handle renewal failure by pausing work that needs the credential; never read rotating values from environment variables; reacquire user-delegated tokens from the grant, not from a stored refresh token **[verified for the last rule, in our model]**.

## Consequences

- Delegation is our code: WP-102 owns the grant store, its expiry and the exchange client. This is more work than configuring Keycloak, and it is the part that the audit trail (M15) and re-authorization (M11) depend on.
- Access tokens cannot be revoked one by one through Keycloak's standard exchange (revocation chains are not available for access tokens **[read-in-docs]**). Containment (M20) relies on short lifetime (300 s), a PDP deny for the agent identity, and grant revocation. The kill-switch test must be designed for that.
- The embedded PDP keeps running on the last approved policy snapshot without the control plane (M27) and adds no process. Policy distribution to the runtime becomes our responsibility (WP-304).
- Cedar adds a native library per platform. The `uber` classifier is about 28 MB and carries five platforms. The container images need a platform matrix (WP-403). Guava must be declared by the consumer.
- Decisions must carry the policy id and the policy-set version (M12). The id map and version recording are our work.
- Each enforcement point must be on the only path to its tool or model. This is an architectural property that the spike did not test: it needs an ArchUnit rule and a runtime check, and it applies to every new route.
- Path rules: the HTTP policy is attached to the configured path (`/tools/*`). Routes outside that path are expected to be uncovered; this is inferred from the configuration and was not tested **[unverified]**. Deny-by-default for all other paths must be configured and tested (open).
- The secret store decision is D-9, decided on 2026-10-19 as section 3 states. The Vault path is not chosen until the Vault integration test passes on 4.9.0. Until then the application rules in section 3 apply to either store, and the Kubernetes Secrets path is the only one for static values.

## Alternatives considered

- **Actorless standard exchange as delegated authority:** rejected. An authenticated exchange client and a platform grant do not add issuer-signed actor proof to an actorless bearer token. Native preview delegation is permitted only in the isolated interoperability profile until the real product path is evidenced; it is not promoted to production by this ADR.
- **Pass the user's token through to tools:** rejected by M11. The spike shows the tool-side check refuses it **[verified]**.
- **OPA sidecar as the default:** not chosen as default. It works and is as fast over localhost, but it adds a process, a hop and bundle delivery, and it is harder to keep running during a control-plane outage (M27). It stays the alternative where OPA is already the organization's standard.
- **OPA Wasm in process:** fastest, verified for our module, but licence, ABI and maintenance are unknown **[unverified]**. Not adopted yet.
- **Quarkus `@PermissionsAllowed` with custom checkers as the policy engine:** not tested. The rules are expressed in Java code, which is harder to review and to version than declarative policies. Kept as a second enforcement point only if tested **[unverified]**.
- **Kubernetes Secrets only:** simpler, but no lease, revocation or dynamic credentials, and rotation needs restarts for environment variables **[read-in-docs]**. Kept for static values.
- **Vault only:** stronger rotation, but it is a service to run and the Quarkus binding in the BOM is at a version whose docs were not read **[unverified]**.

## Amendments (2026-10-19)

The owner decided D-9 on 2026-10-19. Section 3 above now states the decision. The earlier wording was "proposed here as D-9", with no decision and no entry in the plan. The plan's section 9 now has a D-9 row, marked decided.

1. **Secret store (D-9).** Was: proposed, with the choice open. Now: Vault where the organization runs it; Kubernetes Secrets for static values; `quarkus-vault` stays on the BOM's 4.9.0; a Vault integration test on 4.9.0 is required before the secret store is chosen. Status: decided. The Vault integration test is not written or run yet, so the Vault path is not implemented.
2. **Version of the Vault binding.** The docs read in section 3 describes `quarkus-vault` 5.0.0.Beta1, but the BOM pins 4.9.0. The decision keeps 4.9.0. The lease and renewal behaviour of 4.9.0 is therefore still unverified, and the integration test is the check.

### H1: Keycloak compatibility without weakening delegation (2026-10-19)

**Decision:** retain native, issuer-verified actor proof as the delegation baseline.
Do not relax `Rfc8693TokenExchanger` or `ResourceTokenGuard` when `act` is absent;
do not invent an `act` value from request headers, `azp`, an unverified workload
JWT, or a locally shortened `IssuedToken` expiry. This resolves H1 by choosing
strict native delegation over the proposed actorless fallback. Keycloak remains
the authentication recommendation; standard exchange alone is insufficient for
the delegated-runtime guarantee.

The stable-standard baseline is a **negative compatibility test**: real user
JWT verification succeeds, but an exchanged token without actor proof is
refused. The native-delegation profile is an **explicit preview test profile**
using Keycloak 26.8.1 with `token-exchange-delegation` and `parameterized-scopes`.
It is not an implicit deployment upgrade or an approval to use preview features
in production. If production requires stable-only native delegation, select an
issuer that satisfies the same contract or keep that capability disabled until
Keycloak's required feature is accepted. No new custom STS is introduced here.

For the native path, the subject authorizes the particular actor through the
provider's permission/consent flow and the platform's durable delegation grant.
A production hardcoded `may_act` mapper is not a replacement for consent or
permission. Keycloak actor subjects are issuer-scoped service-account user IDs;
bind the grant to the exact verified actor subject, with an immutable registry
mapping if a display/client ID is used. Never assume service-account UUID and
OAuth client ID are interchangeable.

The implementation owner (Claude) must preserve these checks:

1. Verify both input tokens before using their identity or expiry. Require the
   subject/tenant and authenticated workload actor to match the active grant;
   verify each token for its configured issuer and intended audience.
2. Check the whole grant chain before exchange and again after the response.
   A revoked, expired or widened grant never issues usable authority.
3. Verify the response signature/JWKS, exact issuer, one requested audience,
   effective user, tenant, exact expected nearest actor and required ancestor
   chain, request-bounded non-empty scopes, `jti`, issue/expiry times and no
   refresh token. An unsupported multi-hop chain is refused, never flattened.
4. Reject an actual signed JWT whose expiry exceeds any verified bound above.
   Do not merely shorten the returned Java object's metadata while retaining a
   longer-lived bearer. Near-expiry exchanges may therefore fail and require
   reauthorization. Server client lifespan is at most 300 seconds.
5. Treat every non-success HTTP status as failure, independently of the OAuth
   error string; classify retryable transport/5xx failures separately. Responses
   containing an OAuth error or missing required fields cannot count as success.
6. Each resource keeps signature/actor/audience/scope validation, an active
   grant-chain check, fail-closed dependency handling and an atomic one-use replay
   check. Provider access-token revocation is not a substitute for grant checks.
   Required policy and audit checks remain outside the agent.

**Implementation and acceptance:** this amendment changes no Claude-owned
identity code. H2 supplies an isolated, repeatable CI harness and an evidence
checker. Missing/skipped Java tests fail that gate. The current in-process T07
result and script probes do not close the real-provider gate. WP-102 stays
partial until the product exchanger, verifier and guard pass the required real
Java cases, receive GPT review and green CI, and the remaining deliverables are
verified. See [GPT handoff](../handoffs/gpt/WP-102.md).

References: [Keycloak token exchange and native delegation](https://www.keycloak.org/securing-apps/token-exchange),
[RFC 8693 delegation semantics](https://www.rfc-editor.org/rfc/rfc8693.html#section-1.1).
