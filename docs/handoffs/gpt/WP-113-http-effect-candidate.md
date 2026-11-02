# WP-113 candidate: pinned HTTP effect transport

`PinnedHttpEffectTarget` is an explicitly constructed building block for a fixed JSON POST. It is not a CDI bean,
OpenAPI parser, approved service catalog, production gateway, or substitute for the policy and identity boundaries.
A public instance requires an HTTPS endpoint with no userinfo, query, fragment, or dot segments, uses a direct client
with redirects disabled, validates bounded JSON with duplicate-key and trailing-data rejection, fixes the request bytes
and correlation, and sends the ledger's idempotency key. A package-local IPv4 loopback seam exists solely for tests. `GovernedHttpCredentials` can explicitly compose the transport with the
existing `GovernedAsyncDispatch`, so exchange and HTTP work occur in the same scoped callback; the token is not returned
from that identity boundary. The transport only accepts one synchronous callback on its owning thread and checks that
the provider returns its actual result. It compares token audience, issuer, grant, exact scopes and expiry with
server-pinned expectations, then passes the same token to a caller-supplied current-authority fence and rechecks expiry
and the remaining operation budget before dispatch. These metadata checks and supplied fence do not prove signature
verification or current authorization: production composition must supply the verified exchanger and real identity,
policy, task and owner checks. If authority is lost after a terminal target confirmation, the adapter carries only the
non-secret observed outcome to the ledger. The ledger commits that fact and then propagates the authority refusal;
it does not release the result. An unknown target response remains unresolved. If the outcome commit fails, its
commit status can be uncertain and must be read back; the intent may remain unresolved, and a physical effect is not
rolled back.

If the scoped identity boundary reports lost authority without a definitive outcome, the ledger queues the unknown
for reconciliation and propagates the refusal. A refused lookup cannot trigger a replay, even within a replay window.
This does not imply that an already dispatched network operation was cancelled or that a queue write always succeeds.

`PinnedOpenApiEffect` explicitly composes a digest-pinned OpenAPI 3.1 JSON document with the controlled tool
registration and HTTP candidate. It selects one unique POST by operation ID, a required JSON object schema,
and a fixed literal path. The caller supplies a trusted HTTPS origin: document-level servers are ignored, and path or
operation server overrides, parameters, path templates and custom schema dialects are refused. Schema references may
only be pure, acyclic references to named components in the same pinned document; expansion depth, node count and
result size are bounded. Remote references, references with sibling keywords and references in instance-valued schema
fields are refused. Request-body and response objects may likewise reference matching local components without siblings;
missing, cyclic, cross-kind and remote references are refused. Confirmed status codes must be documented with response
descriptions. The gateway fingerprint covers the exact document digest, selected operation, origin,
target, timeout, correlation, confirmation codes and token expectations. The gateway validates arguments against the
selected schema before binding; no credential or network call occurs at registration. This narrow selector is not a
complete OpenAPI validator, approval workflow or interpretation of OpenAPI security schemes; trusted composition must
independently enforce those schemes, approved target/occurrence binding, identity, policy, audit and service semantics.
The supported subset is intentionally smaller than the [OpenAPI 3.1 specification](https://spec.openapis.org/oas/v3.1.0.html).
The PostgreSQL controlled-gateway fixture verifies that a schema-invalid request creates no intent and acquires no
credential, while a valid request reaches a fixture credential refusal and leaves an unresolved intent. The same gateway
case resolves nested component schemas and rejects a value below the referenced minimum before acquiring credentials.
Selector tests cover digest and endpoint changes, duplicate operation IDs, unsupported paths, local-reference cycles,
remote references, dialects and status codes.
Another controlled gateway fixture selects locally referenced request and response objects and calls an IPv4 loopback
HTTP service through the real ledger. It checks the bound request, credential and idempotency key and confirms that an
exact retry does not send again. The test-only loopback seam does not enable plaintext production transport; the fixture
credential, service and policy do not establish provider interoperability or approval.

A trusted service contract must explicitly choose terminal confirmation status codes from 200, 201 and 204, and must
prove those statuses mean the effect is complete. A 202, redirect, unselected status, timeout, I/O failure or missing
lookup is UNKNOWN, never proof of failure. Response bodies are not retained. Replay window is zero, so the ledger does
not automatically resend an unresolved effect. The target must atomically deduplicate any physical retries by the HTTP
implementation; that transport behavior and the remote service's idempotency contract have not been proven. The
[JDK 21 HttpClient documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
notes that interruption does not guarantee when an exchange stops. A caller must account for network delay, in-flight
effects, and clock/retention limits before relying on deduplication.

Sixteen slots are shared by candidate instances in one classloader and are acquired before credential acquisition.
A completed refusal before target dispatch releases its slot; the supplier must independently bound any physical
provider work that can outlive its return. Once HTTP dispatch starts, a confirmed terminal response releases the slot;
an unresolved response, interruption or transport failure permanently reserves it because physical work may continue.
Capacity exhaustion returns UNKNOWN, with no queue. A blocked supplier holds a slot but cannot be forcibly stopped;
this can exhaust availability. Other classloaders, previous processes and remote work after restart are not bounded.

Local loopback tests cover request/key/correlation/credential binding, terminal status selection, refusal of redirects,
timeout uncertainty, invalid or expired tokens, authority refusal after acquisition, shared slot exhaustion and
blocked credential acquisition. Scoped-dispatch tests exercise revocation before and after the target call and reject
escaped or repeated callbacks, using a fixture exchanger and policy. A PostgreSQL case combines the actual grant store,
identity dispatcher and ledger with a fixture exchanger and local service: revocation during a confirmed call retains
the CONFIRMED fact while withholding the result; a non-confirming response under the same revocation remains INTENDED
and enters reconciliation without returning an outcome. Separate cases cover withheld authoritative lookup and compensation;
a later authorized compensation retry reuses the retained confirmation. Another ledger case checks that a genuinely
lost acknowledgement remains INTENDED and is not resent by the ledger. A separate PostgreSQL gateway fixture checks
that an observed-outcome refusal propagates through the registered tool path, the ledger retains its confirmation,
and an authorized retry does not dispatch twice. It uses a fixture target and does not establish the end-to-end HTTP
or production approval composition. The scoped identity and transport cases also passed on Windows. The prior ledger implementation failed the
revocation regression by returning an unresolved result without propagating the refusal. These are controlled
integrations, not real provider interoperability. The first Windows run exposed the process
classpath length limit; the saturation probe now uses a Java argument file. The loopback seam and credentials are
fixtures, not provider interoperability or network isolation evidence.

Before any production registration: integrate an approved OpenAPI source and version lifecycle, prove
per-call exchanged credential/audience and current authority, mandatory policy and audit, exact request/occurrence
binding, service-specific terminal/refusal semantics, remote idempotency and lookup, transport retry/physical-termination
behavior, bounded credential acquisition, encrypted secret handling and egress policy. Add actual enterprise-service
and process/restart tests. The independent project review remains deferred until implementation is complete.
