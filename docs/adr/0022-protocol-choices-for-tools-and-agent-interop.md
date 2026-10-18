# ADR-0022: Protocol choices for tools and agent interoperability

**Status:** Proposed  
**Date:** 2026-10-18

## Context

M23 requires that tools exposed through MCP and agents exposed through A2A are consumable under the same identity and policy controls as everything else. H03 prefers native, secured MCP and A2A over custom REST adapters. WP-007 (Spike E) was time-boxed to find out what is usable on the JVM today, and how those protocols would sit behind our gateways. Its findings are in `docs/spikes/WP-007-mcp-a2a-protocols.md`. The decisive ones:

- `quarkus-langchain4j-mcp` 1.14.2 works on Quarkus 3.39.5 with a streamable HTTP server. The 1.14.2 BOM manages langchain4j 1.20.2, not 1.14.
- A native `McpClientListener` cannot deny a tool call, and a native tool timeout is returned as normal text (`isError=false`). Policy and timeouts must live in our own wrapper.
- A per-call `Authorization` header can be derived from the invocation (verified). No artifact performs RFC 8693 token exchange. `quarkus-langchain4j-oidc-mcp-auth-provider` passes through the inbound request token.
- The A2A Java SDK 1.4.0.Final (spec 1.0) is on Maven Central under `org.a2aproject.sdk`. There is no Quarkiverse A2A client or server artifact. The A2A reference server did not come up in-process in a Quarkus test app, so the served Agent Card and task lifecycle are unverified.
- The protocols provide no delegation scope narrowing and no hop counter for cycle control.

## Decision

1. **Tools: MCP.** Use `quarkus-langchain4j-mcp` as the MCP client, behind a tool gateway of our own (WP-204). The gateway wraps `McpClient` and is the only place that allows, denies, times out, budgets and audits a call. Native listeners are used for observability only.
2. **Identity to tools: per-call grant-derived token.** The gateway sets the outbound `Authorization` header per call from a token obtained by our exchange of the delegation grant (WP-102), through `McpHeadersSupplier`. We do not use `quarkus-langchain4j-oidc-mcp-auth-provider` on any path that carries user authority, because it passes through the inbound token.
3. **Exposing our tools as MCP:** use the Quarkiverse MCP server (`quarkus-mcp-server-http` 2.0.3, streamable HTTP) when we need to host MCP tools. Its tool-parameter header binding is not working in the spike, so identity is read from the request by the gateway, not from tool parameters.
4. **Agent interop: A2A through a port.** Agent-to-agent calls go through an A2A port in our runtime. The first adapter uses the A2A Java SDK 1.4.0.Final client and spec. We do not adopt the Apicurio A2A module (it pins SDK 1.0.0.Final) until it is verified against 1.4.0. We do not treat the in-process reference server as proven until the spike follow-up closes its wiring.
5. **Delegation and cycles are ours.** Scope narrowing and cycle control are enforced by our A2A adapter and gateway, using a message metadata convention (for example `chain` and `hop`) and a per-task delegation record. The protocols are not relied upon for either.
6. **Streaming to a UI and AG-UI are not decided here.** The `langgraph4j-ag-ui-sdk` artifact exists and is evaluated under WP-208.

## Consequences

- Positive: enforcement sits in one place we own and test. The protocols can be replaced behind ports (ADR-0012 pattern). Identity is never passed through unchanged, which matches the plan's section 2.
- Positive: the per-call header path is verified, so WP-204 does not need a new spike for identity transport.
- Negative: we build the token exchange, the narrowing and the cycle detection that the artifacts do not give us. This is in WP-102, WP-204 and WP-207 and adds scope.
- Negative: langchain4j 1.20.2 is pulled in by the "1.14.2" BOM. Upgrades and version reasoning must use the real version. D-8 and the plan's reuse table need correcting.
- Negative: the A2A in-process story is unfinished. WP-207 and WP-208 cannot rely on in-process A2A tests until the reference server is wired (spike follow-up, see the spike document's open questions).
- Risk: MCP spec `2026-07-28` and A2A spec 1.0 are recent. Both sides list the newer versions, but cross-vendor interop is not tested.

## Alternatives considered

- **Use `quarkus-langchain4j-oidc-mcp-auth-provider` as is.** Rejected: passthrough of the inbound token breaks the no-passthrough rule for delegated authority.
- **Use native MCP listeners as the policy point.** Rejected: a throwing listener does not stop the call, and timeouts are silent.
- **Custom REST adapters for tools and agents only.** Rejected for now: H03 prefers native MCP and A2A. Kept as the fallback if the native path fails in a later spike.
- **Use the Apicurio A2A module now.** Rejected for now: it pins SDK 1.0.0.Final and was not run.
- **Use the MCP Java SDK (`io.modelcontextprotocol.sdk`) directly.** Not evaluated. Open question in the spike document; it would remove the langchain4j coupling but adds our own integration with Quarkus.
- **Treat the A2A reference server as the in-process test harness now.** Rejected: it did not serve routes in a Quarkus test app without further wiring, so it cannot yet be relied on for acceptance tests.
