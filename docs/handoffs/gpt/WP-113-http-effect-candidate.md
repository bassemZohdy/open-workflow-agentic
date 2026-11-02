# WP-113 candidate: pinned HTTP effect transport

`PinnedHttpEffectTarget` is an explicitly constructed building block for a fixed JSON POST. It is not a CDI bean,
OpenAPI parser, approved service catalog, production gateway, or substitute for the policy and identity boundaries.
A public instance requires an HTTPS endpoint with no userinfo, query, fragment, or dot segments, uses a direct client
with redirects disabled, validates bounded JSON with duplicate-key and trailing-data rejection, fixes the request bytes
and correlation, and sends the ledger's idempotency key. A package-local IPv4 loopback seam exists solely for tests. The credential supplier returns the existing issued-token type. The
candidate compares its audience, issuer, grant, exact scopes and expiry with server-pinned expectations, then passes
the exact issued token to a caller-supplied current-authority fence and rechecks expiry and the remaining operation
budget before dispatch. These
metadata checks do not prove token signature verification or that the supplied fence is authoritative: trusted
composition must use the verified exchanger and real identity, policy, task and owner checks.

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
blocked credential acquisition. A PostgreSQL ledger test checks that a lost
acknowledgement remains INTENDED and is not resent by the ledger. The Linux/PostgreSQL run passed the ledger case and
19 existing effect cases; seven transport cases passed on Windows and Linux, with the expiry-during-fence assertion
also rerun on Windows. The first Windows run exposed the process
classpath length limit; the saturation probe now uses a Java argument file. The loopback seam and credentials are
fixtures, not provider interoperability or network isolation evidence.

Before any production registration: implement approved OpenAPI operation selection and pinned versioning, prove
per-call exchanged credential/audience and current authority, mandatory policy and audit, exact request/occurrence
binding, service-specific terminal/refusal semantics, remote idempotency and lookup, transport retry/physical-termination
behavior, bounded credential acquisition, encrypted secret handling and egress policy. Add actual enterprise-service
and process/restart tests. The independent project review remains deferred until implementation is complete.
