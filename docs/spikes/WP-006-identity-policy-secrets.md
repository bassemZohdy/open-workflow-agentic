# WP-006 spike: identity, policy and secrets

**Status:** Findings, not a decision · **Date:** 2026-10-18 · **Work package:** [WP-006](../plan/work-packages.md) · **Requirements:** M11, M12 (with M02, M13, M19, M20, M26, M27)

Code: [`spikes/spike-d-identity-policy`](../../spikes/spike-d-identity-policy) (standalone Maven project, 43 tests, not in the root `<modules>`). Decision record: [ADR 0021](../adr/0021-identity-delegation-policy-engine-and-secrets.md).

Confidence labels used below:

- **verified-by-test**: a test in the spike ran and passed in this sandbox.
- **read-in-docs**: read in an official page or in documentation shipped inside an artifact. Not executed here.
- **unknown**: not verified. The reason is given.

Nothing in this report is a verified claim about Keycloak, Vault or core Kubernetes behaviour unless the row says verified-by-test, and no row claims a behaviour of a running Keycloak, Vault or Kubernetes cluster.

## 1. Summary

- **Delegation.** The RFC 8693 request and response shape, our narrowing rules, the delegation chain (`act`), refusal of token passthrough, and re-authorization when a grant expires are verified against our own in-process authorization server. Keycloak is documented (not run) to narrow scope and audience through standard token exchange from version 26.2. Keycloak's delegation (`act`/`may_act`) is preview, and impersonation is not implemented. Our delegation grant store therefore has to be ours.
- **Policy engine.** Cedar embedded is verified in process: default deny, forbid overrides permit, every error or missing policy denies, 0.4 ms p50 per decision. An OPA sidecar is verified with the real `opa` 1.13.2 binary: same decisions, 0.4 ms p50 over localhost HTTP, deny when the sidecar is stopped. OPA compiled to Wasm and run in process is verified for our module: 17 to 25 µs p50. Quarkus `HttpSecurityPolicy` is verified as a policy enforcement point in front of tool calls, and it fails closed.
- **Secrets.** Not verified by running anything. The Quarkus Vault docs we could read describe version 5.0.0.Beta1, but our BOM pins 4.9.0. The Kubernetes rotation facts come from the Secrets Store CSI driver docs, not core Kubernetes. Two primary pages (kubernetes.io and developer.hashicorp.com) were blocked.

## 2. Questions, answers and evidence

| # | Question | Answer | Evidence | Confidence |
|---|---|---|---|---|
| Q1 | Can a user's authority become a short-lived, narrowly scoped, audience-bound token for an agent through RFC 8693? | Yes, in our model: the exchange narrows audience and scope, sets a lifetime of at most 300 s, records `act`, issues no refresh token, and the resource check refuses the user token used directly. This is verified only against our mock server, not against Keycloak. | `TokenExchangeTest.exchangeNarrowsAudienceAndScopeAndRecordsTheDelegationChain`, `resourceServerRejectsTheUserTokenUsedDirectly`, `resourceServerAcceptsOnlyTheTokenIssuedForItsAudience` | verified-by-test (our AS) |
| Q2 | Which Keycloak version supports standard token exchange (V2)? | Keycloak 26.2: the announcement says "Standard Token Exchange is now officially supported in Keycloak 26.2". The current nightly page says standard exchange is "enabled by default once the Keycloak server is started" and needs the client switch "Standard token exchange". Our reading is of a blog post over a year old and a nightly page, not of a 26.x reference page. | https://www.keycloak.org/2025/05/standard-token-exchange-kc-26-2 (fetched); https://www.keycloak.org/nightly/securing-apps/token-exchange (fetched) | read-in-docs |
| Q3 | What does Keycloak standard V2 narrow, and what does it not do? | Audience can only filter, never add. Scope can add optional client scopes; to forbid widening, enable the `downscope-assertion-grant-enforcer` executor. Not implemented: impersonation (listed as "Not implemented yet"), exchange of external IdP tokens (future work). Not supported: the `resource` parameter; exchange requests from public clients; revocation chains for access tokens. The subject token's audience must include the requester, except for self-exchange. | nightly token-exchange page (fetched) | read-in-docs |
| Q4 | Does Keycloak provide delegation (`act`) and delegation grants? | Only in preview, on the legacy path: `--features=token-exchange-delegation,parameterized-scopes`. Requires FGAP v2 and "Consent required" on the requesting client. Without a matching permission the delegation scope is silently dropped and no `may_act` claim is issued. Standard V2 does not do delegation. | nightly token-exchange page (fetched) | read-in-docs |
| Q5 | Refresh tokens? | Standard V2 returns a refresh token only if the client sets "Allow refresh token in Standard Token Exchange". Token exchange never creates a user session. Our design issues none and re-exchanges from the grant instead. | nightly page (fetched); our rule tested in `exchangeNarrowsAudienceAndScopeAndRecordsTheDelegationChain` | read-in-docs (Keycloak); verified-by-test (our rule) |
| Q6 | Re-authorization after a long wait. | Our rule: the delegation grant expires independently of the user token, so an exchange after grant expiry fails with `invalid_grant` ("re-authorization required") even while the user token is valid. Keycloak behaviour: unknown. | `TokenExchangeTest.grantExpiryForcesReauthorizationEvenWhileTheUserTokenIsStillValid` | verified-by-test (our AS); Keycloak unknown |
| Q7 | Agent-to-agent delegation can only narrow? | Yes in our model: a child agent's exchange of a delegated token keeps the chain (`act.act`) and rejects any scope the parent token does not hold. | `TokenExchangeTest.agentToAgentDelegationCanOnlyNarrow` | verified-by-test (our AS) |
| Q8 | What do `quarkus-oidc` and `quarkus-flow-oidc` provide for exchange? | `quarkus-oidc-client` 3.39.5 (managed by the root BOM) has grant type `exchange`, the RFC 8693 grant that requires a `subject_token` at request time. `quarkus-flow-oidc` 1.2.0 depends on it but maps only `client_credentials` and `password`. Workflow tasks cannot use exchange through flow-oidc without custom code. | `quarkus-oidc-client-3.39.5.jar`: config javadoc JSON for `quarkus.oidc-client.grant.type`; `quarkus-flow-oidc-1.2.0.jar`: class constants in `OidcClientConfigFactory` and `EndpointKey` (inspected with unzip and grep, not executed) | read-in-docs |
| Q9 | Cedar (cedar-java) embedded: does it give default deny, forbid overrides, fail closed, and attributable decisions? | Yes. Default deny, forbid overrides permit, an evaluation error is not an allow, a malformed policy file or an empty engine denies, an engine exception denies. The reason names the policy that decided, but as an engine-assigned id (`policy0`, `policy1`), not the `@id` annotation. An id map is needed for attribution. | `CedarToolAuthzTest` (11 tests) | verified-by-test |
| Q10 | Can cedar-java run on this JDK and platform? | Yes, on JDK 21, Linux x86_64, with the `uber` classifier. The default jar has no native library (`cedar_java_ffi`) and fails with `UnsatisfiedLinkError`. The public API exposes Guava types, but Guava is declared runtime-scoped, so the consumer must add it. | Build log of the spike; `CedarLatencyTest` passing | verified-by-test |
| Q11 | OPA sidecar: works, and what is its latency and fail-closed behaviour? | Yes. `opa` 1.13.2 `run --server` on localhost; the PEP denies on any non-200, missing or non-boolean result, or I/O error. Decisions match the Cedar rules. A stopped sidecar leads to deny in 2 to 13 ms. A malformed bundle makes the process exit with status 1. Deploying and updating bundles was not measured. | `OpaSidecarTest` (8 tests); binary from the GitHub release, SHA-256 equal to the release's `.sha256` file | verified-by-test |
| Q12 | Is `com.styra.opa:opa` or `io.github.open-policy-agent:opa` an embedded evaluator? | No. Both POMs describe a client SDK for the OPA REST API ("SDK enabling Java developers to easily integrate with the OPA APIs"). They are not evaluators. | POM descriptions on Maven Central (`com/styra/opa/2.1.1`, `io/github/open-policy-agent/opa/2.2.0`) | read-in-docs |
| Q13 | OPA embedded as Wasm in the JVM: does it work? | Yes for our module. `org.openpolicyagent:opa-java-wasm` 2.0.0 uses the pure-Java Endive runtime. The module was built with `opa build -t wasm -e tool/allow` and evaluates to the same decisions. A malformed module is refused at load. Unknown: the OPA Wasm ABI version the SDK targets, the Endive version, the licence of both, and whether the project is maintained (the repository page shows no release date). | `OpaWasmEmbeddedTest` (3 tests); repository page https://github.com/StyraOSS/opa-java-wasm (fetched, read-in-docs for the description) | verified-by-test for our module; maintenance unknown |
| Q14 | Quarkus security policy as an enforcement point (custom `HttpSecurityPolicy`): does it work? | Yes for tool calls under `/tools/*`. Scope-based permit, denial for missing scope or missing approval, anonymous gets 401, unknown tool gets 403. If the engine throws, the result is 403. If the policy file is broken, every call gets 403. Five path variants of the high-risk route (trailing slash, percent-encoded letter, double slash, dot segment, upper case) all get 403. | `ToolCallHttpPolicyTest` (6), `ToolCallFailClosedTest`, `ToolCallBrokenPolicyTest` | verified-by-test |
| Q15 | Quarkus `@PermissionsAllowed` with custom checkers? | Not tested. The annotation and `PermissionChecker` exist in `io.quarkus.security:quarkus-security` 2.3.2 (the BOM version). Behaviour not run. | Class inspection only | unknown (not tested) |
| Q16 | Does an uncovered path (outside `/tools/*`) default to deny? | Not tested. Only protected paths were exercised. | None | unknown |
| Q17 | Can the tool be reached without passing the PEP (direct service call, in-process call, another route)? | Not tested. It is an architectural property: the PEP must be on the only path. | None | unknown |
| Q18 | Vault via `quarkus-vault`: how do leases and rotation behave? | The root BOM pins `io.quarkiverse.vault:quarkus-vault` 4.9.0. The dev docs we could read document 5.0.0.Beta1, so they are not the spec for 4.9. In those docs: the extension renews tokens and leases inside `renew-grace-period` (default 1H) before max TTL; a dynamic database password becomes invalid when its TTL or max TTL is reached; KV values are cached for `secret-config-cache-period` (default 10M), so a rotated static value reaches the app only after the cache expires; Kubernetes and AppRole auth are listed, JWT is not. Renewal-failure behaviour is not described. The Vault lease page (developer.hashicorp.com) could not be read (blocked). | https://docs.quarkiverse.io/quarkus-vault/dev/index.html (fetched, 5.0.0.Beta1); BOM `quarkus-bom-3.39.5.pom` (4.9.0) | read-in-docs (5.0.0.Beta1 only); Vault semantics unknown |
| Q19 | Kubernetes Secrets rotation: what changes for the app? | From the Secrets Store CSI driver docs (not core Kubernetes): rotation is driven by kubelet's republish cycle when `--enable-secret-rotation` is set; default poll interval 2 minutes; environment variables from synced Kubernetes Secrets are not updated without a pod restart. The core Kubernetes Secrets page (kubernetes.io) could not be read (blocked), so mount-update timing and encryption at rest are not verified. | https://secrets-store-csi-driver.sigs.k8s.io/topics/secret-auto-rotation (fetched) | read-in-docs (CSI driver); core Kubernetes unknown |
| Q20 | What rotation needs from the application | (a) Renew or re-read credentials before TTL, and handle renewal failure. (b) Do not read rotating values from environment variables; read files or re-read on change. (c) Reacquire short-lived tokens from the delegation grant after a long wait, never from a stored refresh token. (c) is verified in our model. (a) and (b) follow from Q18 and Q19. | Q6, Q18, Q19 | (c) verified-by-test (our AS); (a) and (b) read-in-docs |
| Q21 | Is the decision attributable and versioned (M12)? | Attributable: yes, the reason names the deciding policy id (engine-assigned). Versioned: not built. Decisions in the spike do not record a policy-set version. | `CedarToolAuthzTest.forbidOverridesPermitForHighRiskWithoutApproval`, `allowDecisionNamesThePermitPolicy` | verified-by-test (attribution); version recording not built |
| Q22 | Licences and supply chain of the candidates | cedar-java POM: Apache-2.0. `opa-java-wasm` and Endive: licence not checked. OPA binary: not checked beyond the release checksum. | POM of cedar-java 4.10.0 | read-in-docs (cedar-java only); others unknown |

## 3. Measurements

Three full runs of the spike suite on this sandbox (shared CPU, no container runtime). Each run: 2,000 warm-up calls, then 10,000 measured calls. Times are per call on the caller's side, including serialization. p99 varies by a factor of about two between runs on this host. Use the numbers for comparison only.

| Engine and path | Run 1 p50 / p90 / p99 | Run 2 p50 / p90 / p99 | Run 3 p50 / p90 / p99 |
|---|---|---|---|
| Cedar 4.10.0, in process (JNI, JSON per call) | 386.8 / 594.7 / 4495.1 µs | 415.8 / 771.0 / 4606.8 µs | 525.0 / 871.2 / 5948.7 µs |
| OPA 1.13.2 sidecar, localhost HTTP, `/v1/data/tool/allow` | 402.2 / 651.5 / 4054.8 µs | 439.5 / 688.8 / 4246.4 µs | 469.6 / 1132.4 / 4696.2 µs |
| OPA Wasm via opa-java-wasm 2.0.0, in process | 20.5 / 43.8 / 217.8 µs | 16.9 / 30.1 / 48.0 µs | 24.5 / 32.0 / 524.4 µs |
| Quarkus `HttpSecurityPolicy` + Cedar, end-to-end HTTP in the JVM | 1049.0 / 2740.8 / 6554.1 µs | 1149.9 / 3420.4 / 6615.6 µs | 1145.0 / 3195.9 / 7826.6 µs |

Fail-closed timing: a stopped OPA sidecar led to deny in 13.1 ms (run 1 first attempt), 2.1 ms (run 1 full suite), 3.8 ms (runs 2 and 3), measured as the time of the failing decision call.

## 4. What could not be run or read

| Item | Status | Reason |
|---|---|---|
| Keycloak (any version) | Not run | No container runtime; docs.keycloak.org refused by the proxy (`connect_rejected`, 403). Keycloak facts come from www.keycloak.org pages. |
| Vault server | Not run | No container runtime, no Vault binary. |
| Core Kubernetes Secrets page | Not read | Blocked: WebFetch to kubernetes.io was refused by an unanswered permission prompt. Not retried. |
| Vault lease documentation | Not read | Blocked: WebFetch to developer.hashicorp.com was refused in the same way. Not retried. |
| Quarkus `@PermissionsAllowed` custom checkers | Not tested | Time-box; `HttpSecurityPolicy` was the rule type used. |
| Default behaviour of uncovered paths; direct-call bypass | Not tested | Architectural; open questions. |
| GitHub API (`api.github.com`) | Refused (403) | Release listing not used. The binary was taken from the release download URL, which worked, and matched its published SHA-256. |
| OPA bundle distribution and decision logs | Not tested | Out of spike scope. |

## 5. What this means for the plan

| Work package | Assumption that changes | Consequence |
|---|---|---|
| WP-102 identity and delegated authority | Keycloak gives delegation. | Delegation grants and their expiry are ours, stored in our database. Keycloak can only issue the narrowed token (standard V2, 26.2 or later). Build an integration test against a real Keycloak in CI, because the fake IdP in WP-002 cannot prove Keycloak behaviour. |
| WP-102 (secrets part) | Secret rotation is a library feature. | Rotation rules are ours: renew before TTL, no secrets in environment variables, reacquire from the grant. Store choice is pending the owner (see section 7). |
| WP-005 workflow-to-agent bridge | The workflow HTTP client can exchange tokens through quarkus-flow-oidc. | It cannot (Q8). The bridge needs a custom auth provider or a direct `quarkus-oidc-client` exchange grant. This risk sits upstream of WP-102. |
| WP-103 policy enforcement points | The PEP is a framework feature. | Use a `Pdp` port with Cedar embedded behind it (recommended, see section 7). For HTTP use a Quarkus `HttpSecurityPolicy`. For in-process calls use a call-site wrapper. Policy ids and versions must be mapped and recorded by us (Q21). |
| WP-204 tool gateway and MCP | Policy overhead is negligible. | About 0.4 ms p50 per decision in process; about 1.1 ms end-to-end over HTTP here. Acceptable for tool calls, but sidecar deployment adds a process and a network hop. |
| WP-304 separation of authority and admin-outage safety | A policy engine can be run as a sidecar with a control plane outside. | Embedded Cedar with the last approved policy snapshot keeps running without the control plane (M27) with no extra process. The sidecar needs its own bundle delivery and a freshness rule. Bundle delivery was not measured. |
| WP-305 emergency containment | Access tokens can be revoked. | Keycloak standard V2 does not revoke access-token chains (Q3). Containment relies on short token lifetime (300 s in our model), a PDP deny for the agent identity, and grant revocation. The kill-switch test (T11) must be designed around this. |
| WP-306 data security and WP-404 tenancy | One secret store fits all. | Vault policies per tenant or Kubernetes namespaces both need design. Not decided here. |
| WP-001 build and WP-307 supply chain | Dependencies are plain JARs. | cedar-java needs the `uber` classifier (about 28 MB, native libraries for five platforms). Guava must be declared. Licences of `opa-java-wasm` and Endive are unchecked (H23). |
| WP-402 upgrades (M21) | Stable library versions. | quarkus-vault: the BOM pins 4.9.0 while the docs read are for 5.0.0.Beta1. Cedar and the OPA Wasm ABI need upgrade tests. |

## 6. Open questions

1. Does a live Keycloak 26.2+ (or the version the owner runs) produce the narrowed, audience-bound tokens and the `act` claim we expect? This needs a test with a real Keycloak in CI.
2. Does Keycloak standard V2 need a client registration for each audience or agent, and how does that map to the inventory (M18)?
3. Is there any Keycloak path to delegation that is not preview? Not found in the pages read.
4. Vault: renewal failure behaviour in quarkus-vault 4.9.0; dynamic database credentials on a long-running workflow; the Vault lease semantics themselves (page blocked).
5. Kubernetes: core Secret mount update timing and encryption at rest (page blocked).
6. Quarkus: default behaviour for paths that no permission rule covers, and whether a deny-all default can be set; `@PermissionsAllowed` as a second enforcement point.
7. Non-bypass: how do we guarantee that every tool and model call passes the PEP (architectural test, possibly an ArchUnit rule plus a runtime check)?
8. Cedar: how is the policy-set version recorded in each decision? Engine-assigned ids change if the file is reordered, so a stable id map is needed.
9. OPA: bundle delivery, decision logs and the Wasm ABI compatibility matrix for newer OPA releases.
10. Endive and opa-java-wasm: licences and maintenance status.
11. Cedar in production: native library size (about 28 MB uber jar) and platform matrix for the container images (WP-403).

## 7. Recommendation for owner decisions D-1 and D-2

### D-1 Identity provider: keep Keycloak, pinned to 26.2 or later, conditionally

- Use Keycloak for user OIDC and as the issuer of narrowed tokens through standard token exchange. Standard V2 covers what the plan needs: audience filtering, scope narrowing (with the downscope enforcer), and refresh control. Evidence: Q2, Q3 (read-in-docs).
- Do not rely on Keycloak for delegation or impersonation. Delegation is preview, impersonation is not implemented (Q4). Our grant store and re-authorization rule (Q6, Q7, verified-by-test) stay in WP-102.
- Condition: before WP-102 closes, pass an integration test against a real Keycloak in CI. Nothing in this spike proves Keycloak's behaviour.
- If the owner does not want Keycloak, nothing in the spike is tied to it. The client follows RFC 8693, so any compliant authorization server can be used, but that alternative was not evaluated.

### D-2 Policy engine: Cedar embedded by default; OPA sidecar only where OPA already runs

- Cedar embedded behind the `Pdp` port. Reasons, all verified-by-test: default deny, forbid overrides permit, every failure mode denies, 0.4 ms p50 per decision with no extra process, and it keeps working on the last approved snapshot when the control plane is down (M27). The main cost is the native library: the uber jar is about 28 MB and needs the `uber` classifier.
- OPA sidecar where the organization already runs OPA and wants one policy language. It is verified with the real binary, with a similar p50 (0.4 ms over localhost). It adds a process, a network hop, bundle delivery, and a fail-closed rule for control-plane outages that we have not measured.
- Not evaluated here: policy testing tooling, decision log tooling, and OPA's data and bundle features.
- OPA Wasm in process is fast (17 to 25 µs p50) and verified for our module, but its licence and maintenance status are unknown. Do not adopt it before those are checked.
- Enforcement points: Quarkus `HttpSecurityPolicy` for HTTP (verified), and a call-site wrapper for in-process calls (not yet built). Whichever engine is chosen, the PEP library hides it, as the plan's risk R6 requires.

### Proposed decision not yet in the plan: secrets (D-9, for owner decision)

- Vault where the organization already runs Vault: dynamic credentials and lease renewal are the rotation mechanism. Evidence is docs only (Q18), so it needs a Vault integration test.
- Kubernetes Secrets for static values only, read from files and reloaded or restarted on change (Q19). Encryption at rest is unverified.
- In both cases the application renews before TTL and never reads rotating values from environment variables (Q20).

## 8. Commands run

```
mvn -B -q dependency:resolve                      # spike pom, ok
mvn -B -Dtest='CedarToolAuthzTest,CedarLatencyTest' test
mvn -B -Dtest='TokenExchangeTest' test
OPA_BIN=<scratchpad>/opa/opa_linux_amd64_static mvn -B -Dtest='OpaSidecarTest' test
mvn -B -Dtest='OpaWasmEmbeddedTest' test
mvn -B -Dtest='ToolCallHttpPolicyTest,OpaWasmEmbeddedTest' test
OPA_BIN=<scratchpad>/opa/opa_linux_amd64_static mvn -B clean test        # full suite, twice
opa build -t wasm -e tool/allow tool_authz.rego -o bundle.tar.gz          # Wasm module for the embedded test
```

Final full-suite result: 43 tests, 0 failures, 0 skipped (`BUILD SUCCESS`, OPA_BIN set). An earlier full run failed on two attribution assertions that were wrong; they were corrected to the engine's policy ids (commit `f478661`) and the suite was run again in full.
