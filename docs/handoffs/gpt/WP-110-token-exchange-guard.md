# WP-110 token-exchange guard: identity owner intake

Requester: GPT (resilience). Producer/consumer owner: Claude (WP-102 identity).
Status: **proposed, not approved or activated**. This branch contains an
experimental integration for review; it does not establish an approved
cross-owner contract, production protection, or WP-110/T17 completion.

## Proposed boundary

The GPT-owned `runtime/resilience/DependencyGuard` supplies immediate bounded
concurrency admission and circuit breaking. The proposed consumer is the
Claude-owned `runtime/identity/Rfc8693TokenExchanger`: guard a single HTTP
exchange, never retry it, and preserve its existing typed outcomes. The legacy
constructor remains unguarded, and `IdentityProducers` still uses it: no
production wiring or configuration is introduced. No schema change is proposed.

The experiment treats HTTP 200/400/401/403 as dependency responses and other
statuses or I/O failures as outages. Rejections map to `UNAVAILABLE` without a
request. An interrupted call preserves the interrupt and is neutral for the
breaker. This classification and its safety for the supported providers require
identity-owner review; in particular, status 429, malformed or oversized 200
responses, token issuance after a lost response, and audit/error attribution
need an explicit decision. A successful HTTP response is not proof of a valid
credential or current caller authority. Never retry token issuance merely
because its response is lost.

## Activation and evidence requested

Please review the exact branch head and either adopt or amend the consumer
contract, ownership of the identity changes, failure classification, settings,
sharing/lifecycle of a guard per endpoint, and default behavior. Before any
production wiring, prove bounded response-body completion (the existing stream
read is not established as bounded by the request timeout), interruption and
half-open recovery, full concurrent overload shedding, no retry or duplicate
token issuance, audit and caller freshness, and correct behavior with the
supported real identity provider. Preserve the existing verified-caller,
grant, audience, scope and expiry checks; a guard is not authorization.

### Response-body timeout diagnostic

On the local Windows/JDK environment, a separate loopback HTTP probe used the
same JDK `HttpClient.send(..., BodyHandlers.ofInputStream())` and `readNBytes`
pattern. The server flushed HTTP 200 headers, delayed its two-byte body by
1,500 ms, then completed it. A request timeout of 500 ms still returned both
bytes after 1,708 ms. This demonstrates that the request timeout alone did not
bound the body read in that probe; it is not an end-to-end exchanger or
supported-provider test. A stalled body can therefore keep the proposed guard
permit occupied beyond the configured timeout. The identity owner should
provide a bounded-body implementation and a deterministic actual-exchanger
stall/cleanup test before activation. Any cancellation design must retain the
concurrency bound until the physical read has stopped, and must not retry an
exchange whose server-side outcome is unknown.

At initial head `b4881b6`, 20 guard unit cases and six fake-provider exchange
cases passed locally, the latter against isolated PostgreSQL 16. This is not
real-provider evidence, independent review, full CI, or acceptance evidence.
Any later head requires its own checks and independent review. WP-102 and
WP-110 remain partial; T17 remains open.
