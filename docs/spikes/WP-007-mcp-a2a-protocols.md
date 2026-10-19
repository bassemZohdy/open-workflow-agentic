# WP-007 Spike E: MCP, A2A and agent protocols

**Status:** Findings · **Date:** 2026-10-18 · **Owner:** WP-007 · **Code:** `spikes/spike-e-protocols/` (standalone Maven project, not in the root build)

Confidence labels:

- **verified-by-test**: a test in the spike project ran and passed (or a documented failure was observed by a test).
- **read-in-jar**: the class, method or string was read from the artifact with `javap` or `unzip` on the resolved jars. Not executed.
- **read-in-POM**: read from the artifact's POM only.
- **read-in-docs**: read from documentation or a web page. None of the claims below rests on this label alone.
- **unknown**: not established.

## Questions

| # | Question | Answer | Evidence | Confidence |
|---|---|---|---|---|
| Q01 | MCP artifacts for Quarkus 3.39 and Quarkus LangChain4j 1.14.2 | `io.quarkiverse.langchain4j:quarkus-langchain4j-mcp` 1.14.2 and `quarkus-langchain4j-oidc-mcp-auth-provider` 1.14.2 exist and resolve with the root BOMs (Quarkus 3.39.5). Latest on Central: both **2.0.0.Beta1** (lastUpdated 2026-10-15). The 1.14.2 BOM manages `langchain4j` **1.20.2**, and the MCP module brings `langchain4j-mcp` 1.20.2-beta30. Server side: `io.quarkiverse.mcp:quarkus-mcp-server-http` 2.0.3 (built for Quarkus 3.33, requires `[3.33,)`). | `mvn dependency:tree` in the spike (BUILD SUCCESS); `maven-metadata.xml` for each artifact (curl through the proxy); POMs of 1.14.2 artifacts | verified-by-test (resolution, Q02 runs on 3.39.5); read-in-POM (versions) |
| Q01a | MCP spec version and transports | Client: protocol version strings `2024-11-05`, `2025-11-25`, `2026-07-28` in `langchain4j-mcp`. Transports in the client: stdio, streamable HTTP, websocket (no separate legacy-SSE client class found). Server: `McpProtocolVersion` lists 2024-11-05 through 2026-07-28; streamable HTTP at `/mcp`; SSE at `/mcp/sse` for 2024-11-05. | `javap` and `strings` on `langchain4j-mcp-1.20.2-beta30.jar` and `quarkus-mcp-server-core/http-2.0.3.jar`; config javadoc in `quarkus-mcp-server-http-2.0.3.jar` | read-in-jar. Streamable HTTP transport verified-by-test (Q02). SSE and websocket not run. |
| Q02 | In-process MCP server, two tools, streamable HTTP, gateway with policy, audit, timeout and budget | Works. Client calls through `ToolGateway` (decorator over `McpClient`): allow, deny by policy (server counter proves the denied call never arrives), deny when no principal, timeout (gateway configured at 300 ms against a 3 s tool; asserted to return under 2 s), per-invocation budget (third call refused, server not reached), audit event per decision. | `Q02_McpGatewayTest` (8 tests, all pass, default build) | verified-by-test |
| Q02a | Do native listeners or timeouts enforce policy? | **No.** A throwing `McpClientListener.beforeExecuteTool` does not stop the call. The native `toolExecutionTimeout` bounds the wait, but the timeout comes back as normal text with `isError=false`. | `Q02_nativeListenerCannotDenyToolCall`, `Q02_nativeToolExecutionTimeoutReturnsNonErrorText` | verified-by-test |
| Q03 | How the client attaches a caller identity per call | `StreamableHttpMcpTransport.Builder.customHeaders(McpHeadersSupplier)` receives the per-call `McpCallContext`, so each call can carry its own `Authorization` header derived from `InvocationContext.invocationParameters()`. Verified: server sees `Bearer token-alice`, then `Bearer token-bob`. The Quarkiverse `@McpParamHeader` tool parameter stayed `null` while the raw header was present (cause not established). | `Q03_perCallAuthorizationHeaderReachesServerForEachCall` (passes; header read via `CurrentVertxRequest`); `Q03_mcpParamHeaderBindingPopulatesToolParameter` (`@Disabled`, observed `null`) | verified-by-test |
| Q03a | Per-user **exchanged** token instead of a static one | **Not provided by any artifact.** The header supplier can carry any token it is given, so a per-call exchanged token is a mechanism we build. A fake exchange (no IdP in sandbox) proves the mechanism only. `OidcMcpAuthProvider` (1.14.2) reads the inbound request's `TokenCredential` from ArC and sends it as Bearer, which is **passthrough**, not exchange. | `Q03_exchangedNarrowTokenCanBeDerivedPerCall` (mechanism only); `Q03_oidcProviderSendsInboundRequestTokenNotExchangedToken` (`@Disabled`: needs quarkus-oidc and an IdP) | verified-by-test (mechanism); read-in-jar (passthrough) |
| Q04 | Java A2A SDK on Maven Central, spec version, artifacts, Agent Card | `org.a2aproject.sdk` group: `a2a-java-sdk-*` **1.4.0.Final** (lastUpdated 2026-09-28). Protocol constant `AgentInterface.CURRENT_PROTOCOL_VERSION = "1.0"`. Client artifact `a2a-java-sdk-client`; reference JSON-RPC server `a2a-java-sdk-reference-jsonrpc` (not a Quarkus extension). Server accepts `A2A-Version` 1.0 and 0.3 (`VersionRouter`). Agent Card route `/.well-known/agent-card.json`. Older group `io.github.a2asdk` stops at 1.0.0.Alpha3 (2026-02-19). **No A2A client or server artifact in `io.quarkiverse.langchain4j`** (both 404). | Maven metadata; `javap`; `strings` on `A2AServerRoutes` and `VersionRouter` | read-in-jar (spec, routes). Agent Card model verified-by-test (`Q04_agentCardModelCarriesProtocolVersionAndInterface`). **Served card not verified:** 404 in-process. |
| Q05 | Two in-process agents: send, observe states, cancel | **Not verified.** Test written against the SDK client and the reference server, compiled under `-Pa2a`, but the reference server does not serve routes in a Quarkus test app, so all three tests are `@Disabled` (observed: `A2AClientHTTPError: Failed to obtain agent card: 404`). Task states exist: `SUBMITTED, WORKING, INPUT_REQUIRED, AUTH_REQUIRED, COMPLETED, CANCELED, FAILED, REJECTED, UNSPECIFIED`. Client has streaming (`ClientConfig.setStreaming`), `subscribeToTask`, and push-config methods. | `Q05_*` (3 `@Disabled`); `javap` on `TaskState`, `Client`, `AgentEmitter` (`requiresInput`, `startWork`, `cancel`) | read-in-jar (states, API). Runtime behaviour **unknown**. |
| Q06 | How delegated identity travels in A2A calls | Client: `ClientCallContext(state, headers)` sends per-call headers. Server: `CallContextFactory` builds `ServerCallContext` with a `User`; executor reads `getCallContext().getUser()`. Scope narrowing: **not provided**. `TaskOperation` is a fixed enum (get, list, cancel, send, subscribe, push-config CRUD); `TaskAuthorizationProvider` decides per operation; there is no scope or delegation claim in the SDK. Security schemes can be declared in the Agent Card (`securitySchemes`, `securityRequirements`). | javap on `ClientCallContext`, `CallContextFactory`, `ServerCallContext`, `TaskOperation`, `TaskAuthorizationProvider`, `AgentCard$Builder`. `Q06_perCallBearerHeaderBecomesServerSideUser` (`@Disabled`); `Q06_childDelegationCanOnlyNarrowScope` (`@Disabled`, not provided) | read-in-jar. Runtime **unknown**. Narrowing: not provided (read-in-jar). |
| Q07 | Cycle detection A → B → A | The protocol has `taskId`, `contextId`, `Message.referenceTaskIds`, and free-form `metadata` on messages and config. **No hop counter or chain field** in the SDK. Cycles must be detected by our convention in metadata (for example `chain`, `hop`) or by our own call graph. | javap on `Message$Builder`, `Task`, `ClientConfig$Builder`. `Q07_*` (2 tests, `@Disabled`: metadata round-trip and context reuse, not run) | read-in-jar. Round-trip **unknown**. |
| Q08 | Apicurio registry integration for agent cards or schemas | `io.quarkiverse.langchain4j:quarkus-langchain4j-a2a-apicurio-registry` exists (1.14.2; 2.0.0.Beta1 latest). It depends on `langchain4j-agentic-a2a`, `a2a-java-sdk-spec` and `a2a-java-sdk-client` **1.0.0.Final** (older than our 1.4.0.Final), and `apicurio-registry-java-sdk` 3.3.0. Not run; source not read. | `quarkus-langchain4j-a2a-apicurio-registry-1.14.2.pom` (read-in-POM) | read-in-POM. Behaviour **unknown**. Version mismatch with SDK 1.4.0 is a risk. |
| Q08a | AG-UI for streaming to a UI (WP-007 deliverable) | `org.bsc.langgraph4j:langgraph4j-ag-ui-sdk` **1.9.5** exists on Central (lastUpdated 2026-10-13). Not run. | Maven metadata | read-in-docs (existence only). Evaluation **unknown**. |
| Q09 | Maturity, versions, release dates, risks | See the table below. Dates are Maven `lastUpdated` stamps, not first-release dates. | Maven metadata | read-in-docs |

### Maturity summary (Q09)

| Artifact | Version(s) seen | Last updated (Central) | Assessment |
|---|---|---|---|
| `quarkus-langchain4j-mcp` | 1.14.2 (used); 2.0.0.Beta1 (latest) | 2026-10-15 | Works on Quarkus 3.39.5 (tested). Pulls langchain4j 1.20.2, so "1.14" does not mean langchain4j 1.14. Beta line exists. |
| `quarkus-langchain4j-oidc-mcp-auth-provider` | 1.14.2; 2.0.0.Beta1 | 2026-10-15 | Passthrough of inbound token (read in bytecode). Not run. |
| `quarkus-langchain4j-a2a-apicurio-registry` | 1.14.2; 2.0.0.Beta1 | 2026-10-15 | Pins A2A SDK 1.0.0.Final. Not run. |
| `quarkus-mcp-server-http` (Quarkiverse) | 2.0.3 | 2026-10-08 | Tested as in-process server on Quarkus 3.39.5. Declares Quarkus 3.33 build. |
| `io.modelcontextprotocol.sdk:mcp-core` | listed latest 2.2.0-M1 (milestone); 2.1.0, 2.0.1 before it | not read | Not used. Alternative to langchain4j's MCP client; not evaluated. |
| `org.a2aproject.sdk:a2a-java-sdk-*` | 1.4.0.Final (latest); 1.0.0.Final (pinned by Apicurio module) | 2026-09-28 | Newer line, spec 1.0. Reference server does not run in-process without more wiring (see Q05). |
| `io.github.a2asdk:*` | up to 1.0.0.Alpha3 | 2026-02-19 | Superseded group name. Do not use. |
| `langgraph4j-ag-ui-sdk` | 1.9.5 | 2026-10-13 | Exists. Not evaluated. |

**Risks:** (1) the A2A reference server is not a Quarkus extension and does not come up in a Quarkus test app; wiring it needs `JSONRPCHandler` and `VertxSecurityHelper` to be produced by us (neither has a bean-defining annotation; `AgentCardCacheMetadata` is already a bean), and we did not finish that. Under `-Pa2a`, augmentation also stalls in ArC bean discovery (see "In-process wiring (issue #25)"). (2) The 1.14.2 BOM and beta lines are easy to mix up. (3) MCP spec `2026-07-28` is recent; client and server both list it, but interop was not tested.

## Test status

Default build (`mvn test` in `spikes/spike-e-protocols`): 12 run, 10 pass, 2 `@Disabled`. Under `-Pa2a`: 21 run, 11 pass, 10 `@Disabled`. Both builds BUILD SUCCESS.

## Commands and results

| Command | Result |
|---|---|
| `mvn -B -ntp dependency:tree` in spike | BUILD SUCCESS; MCP 1.14.2 resolves langchain4j 1.20.2 |
| `mvn dependency:copy-dependencies` (test scope) | BUILD SUCCESS; jars inspected with `unzip` and `javap` |
| `mvn test -Dtest=Q02_McpGatewayTest` (first, A2A reference server on test classpath) | Quarkus augmentation was still running after more than 10 minutes (CPU-bound in ArC bean discovery). Killed. Not reproduced later; cause not established. |
| `mvn test -Dtest=Q02_McpGatewayTest` (A2A out of default deps) | 44 s; 8 run, 3 errors (test defects, fixed) |
| `mvn test` default, after fixes | Q02: 8 pass. Q03: 2 pass, 2 `@Disabled`. |
| `mvn -Pa2a test` | 21 run, 11 pass, 10 `@Disabled`. Augmentation about 20 s. Agent Card 404 (see Q05). |

Environment notes: Maven Central returned HTTP 429 once and recovered. GitHub was not attached to this session (`gh` 403; the `add_repo` step was not taken), and a WebFetch of the a2aproject repository was refused by the permission gate. So no claim here rests on the GitHub README or release notes. The spike ports are on 18791 because other sessions use 8081.

## What this means for the plan

| Work package | Assumption in the plan | Change |
|---|---|---|
| WP-204 Tool gateway and MCP | "Reuse" the MCP client for tools; the gateway is ours | Confirmed and sharpened. Native listeners cannot deny calls and native timeouts are silent (`isError=false`). The gateway must wrap `McpClient` (done in the spike as `ToolGateway`). Per-call identity via `McpHeadersSupplier` works. |
| WP-102 Identity and delegated authority (M11) | Token exchange (RFC 8693), never passthrough (plan section 2) | The OIDC MCP provider is passthrough. Exchange must be built by us as the per-call header source. Do not adopt `quarkus-langchain4j-oidc-mcp-auth-provider` for the production path. |
| WP-207 Multi-agent and A2A (M31, M23, M11) | "A2A Java SDK, Apicurio registry integration: to be confirmed in WP-007" | Partly confirmed: SDK 1.4.0.Final and the spec 1.0 API exist. The in-process server is not confirmed. The Apicurio module pins SDK 1.0.0.Final. Delegation narrowing and cycle control are not provided and must be built (metadata convention plus gateway). |
| WP-208 Interactive human-agent collaboration (M32) | Durable input-required | The `INPUT_REQUIRED` state exists in the spec and SDK (read-in-jar). Runtime behaviour not verified. AG-UI artifact exists but was not evaluated. |
| WP-006 Spike D, identity and policy | Decision on delegation | Q03 shows the per-call header path; the policy engine decision is unaffected. |
| WP-402 Safe upgrades; plan section 3 and decision D-8 | "Quarkus LangChain4j 1.14.2 ... stay on 1.14" | Correct the assumption: the 1.14.2 BOM manages langchain4j 1.20.2 and the MCP module uses 1.20.2-beta30. Record this in the plan's reuse table when the plan is next revised (not edited here). |
| WP-113 and WP-406 | Not MCP/A2A specific | No change. |

## In-process wiring (issue #25)

**Status:** not closed. Routes are still not served, and no A2A test ran.

- **Root cause of the 404 (read-in-jar).** `a2a-java-sdk-reference-jsonrpc` 1.4.0.Final has `A2AServerRoutes`, a `@Singleton` with an `@Observes Router` method that registers `POST /`, `GET /.well-known/agent-card.json` and a tenant-regex variant. It injects `JSONRPCHandler`, `AgentCardCacheMetadata`, `VertxSecurityHelper` and `Executor`. `JSONRPCHandler` (`transport-jsonrpc`) and `VertxSecurityHelper` (`reference-common`) have no bean-defining annotation, so nothing produces them. `AgentCardCacheMetadata` is `@ApplicationScoped` in `server-common` (read-in-jar), so the earlier note that it was missing was wrong. `DefaultRequestHandler`, `InMemoryTaskStore`, `InMemoryQueueManager`, `MainEventBusProcessor` and `InMemoryPushNotificationConfigStore` are annotated.
- **Added (untested at runtime).** `src/test/.../a2a/CaseA2AWiring.java` produces `JSONRPCHandler` (public 3-argument constructor, `@PublicAgentCard` card, `RequestHandler`, `Executor`), `VertxSecurityHelper` (public no-arg constructor) and a virtual-thread `Executor`. `CaseAgentExecutor` and `CaseAgentCard` were already in place. It compiles under `-Pa2a` (`mvn -Pa2a test-compile`, BUILD SUCCESS). It was not run, so it may still be wrong.
- **Blocker found while testing.** Under `-Pa2a`, Quarkus augmentation does not finish on this host. Thread dumps (`jstack`, twice, same stack) show the build thread CPU-bound in ArC `AutoAddScopeProcessor` (`implementsInterface`) → Jandex `CompositeIndex.getClassByName` during `BeanDeployment.findBeans`. Runs reached 10 to 15 minutes with no output. Excluding `a2a-java-sdk-spec-grpc` from the client did not change it. The default build (no A2A) is not affected. The stall was seen before this change (the first baseline run) and after it, so the wiring was never exercised.
- **Tests.** No test changed from disabled to enabled. Each `@Disabled` reason in Q04, Q05, Q06 (the first test only) and Q07 now says what is missing and where the stall is. `Q06_childDelegationCanOnlyNarrowScope` is unchanged: the protocol does not provide scope narrowing.
- **Next step.** Find which index or extension makes `AutoAddScope` slow (for example by bisecting the test-scope dependencies, or running on a host with more CPU), then run `Q04` to `Q07` under `-Pa2a` with `CaseA2AWiring`.

## Open questions

1. How to wire the A2A reference server in a Quarkus test app. See "In-process wiring (issue #25)" above: the missing beans are `JSONRPCHandler` and `VertxSecurityHelper`, and augmentation stalls under `-Pa2a`. Whether to keep the produced beans or use another A2A server (for example a plain Vert.x route on the SDK's JSON-RPC handler) is open. Q04's served card and Q05 to Q07 depend on it.
2. Why `@McpParamHeader` does not populate a tool parameter in this setup (Q03). The raw header is present, so the header path itself works.
3. Whether `quarkus-langchain4j-a2a-apicurio-registry` works with SDK 1.4.0.Final or only with 1.0.0.Final.
4. Whether `io.modelcontextprotocol.sdk` (MCP Java SDK, 2.x line) should be the client instead of langchain4j's MCP module. Not evaluated.
5. SSE and websocket MCP transports: not run.
6. Real IdP token exchange (RFC 8693) with Keycloak (D-1): not run; no IdP in the sandbox.
