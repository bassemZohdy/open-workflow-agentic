# WP-113 candidate: pinned HTTP effect transport

`PinnedHttpEffectTarget` is an explicitly constructed building block for a fixed JSON POST. It is not a CDI bean,
OpenAPI parser, approved service catalog, production gateway, or substitute for the policy and identity boundaries.
A public instance requires an HTTPS endpoint with no userinfo, query, fragment, or dot segments, uses a direct client
with redirects disabled, validates bounded JSON with duplicate-key and trailing-data rejection, fixes the request bytes
and correlation, and sends the ledger's idempotency key. A package-local IPv4 loopback seam exists solely for tests. The credential supplier receives the pinned audience and must itself obtain
and validate current, target-scoped credentials within a finite deadline; this component does not prove that authority.

A trusted service contract must explicitly choose terminal confirmation status codes from 200, 201 and 204, and must
prove those statuses mean the effect is complete. A 202, redirect, unselected status, timeout, I/O failure or missing
lookup is UNKNOWN, never proof of failure. Response bodies are not retained. Replay window is zero, so the ledger does
not automatically resend an unresolved effect. The target must atomically deduplicate any physical retries by the HTTP
implementation; that transport behavior and the remote service's idempotency contract have not been proven. The
[JDK 21 HttpClient documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)
notes that interruption does not guarantee when an exchange stops. A caller must account for network delay, in-flight
effects, and clock/retention limits before relying on deduplication.

Sixteen slots are shared by candidate instances in one classloader. A confirmed terminal response releases a slot; an
unresolved response, interruption or transport failure permanently reserves it because physical work may continue.
Capacity exhaustion returns UNKNOWN, with no queue. This is deliberately fail-closed and can exhaust availability; it
does not bound credential acquisition, other classloaders, previous processes, or remote work after restart.

Local loopback tests cover request/key/correlation/credential binding, terminal status selection, refusal of redirects,
timeout uncertainty, invalid configuration and shared slot exhaustion. A PostgreSQL ledger test checks that a lost
acknowledgement remains INTENDED and is not resent by the ledger. The Linux/PostgreSQL run passed the ledger case and
19 existing effect cases; five transport cases passed on Windows and Linux. The first Windows run exposed the process
classpath length limit; the saturation probe now uses a Java argument file. The loopback seam and credentials are
fixtures, not provider interoperability or network isolation evidence.

Before any production registration: implement approved OpenAPI operation selection and pinned versioning, prove
per-call exchanged credential/audience and current authority, mandatory policy and audit, exact request/occurrence
binding, service-specific terminal/refusal semantics, remote idempotency and lookup, transport retry/physical-termination
behavior, bounded credential acquisition, encrypted secret handling and egress policy. Add actual enterprise-service
and process/restart tests. The independent project review remains deferred until implementation is complete.
