# WP-107 / WP-105 — local listener registration race

The pinned SDK (`serverlessworkflow-impl-core:7.35.3.Final`) can return a successfully
registered listener that will never receive another event. Its `AbstractTypeConsumer.register`
selects a type bucket with `registrations.computeIfAbsent(...)` and calls `.add(registration)`
after that map operation returns. Concurrent `unregister` can remove the last old listener,
remove the bucket from the registration map and remove the transport's topic callback before
the new listener is added to the detached bucket. Repeated publication cannot repair it.

`EventRegistrationRaceTest` reproduces this ordering against the actual pinned implementation.
It replaces only the private map with a `ConcurrentHashMap` subclass that pauses after the
real `computeIfAbsent` selects the old bucket. With the raw SDK, removal finishes before the
add continues; publication reaches neither the removed listener nor the new one. This test
uses reflection only to place that deterministic scheduling barrier. It fails rather than
skipping if the pinned implementation changes. No production reflection or SDK source copy
is introduced.

`AtomicInMemoryEvents` synchronizes whole public registration and unregistration mutations.
The same test verifies that removal blocks on the registering thread's monitor until the
add finishes, then publication reaches the surviving listener exactly once. Later removal
still works. A separate test blocks a real published callback and verifies that unrelated
registration/removal can finish before the callback returns. Publishing, predicates and
workflow callbacks are outside the new mutation monitor. The SDK's existing broadcast,
filter, correlation, callback-failure and wildcard behavior is otherwise retained.

`AtomicLocalEventsCustomizer` installs this broker into the real injected application only
when neither an explicit event-consumer bean nor an explicit event-publisher bean is
configured, and no transport service provider is registered through Java's service loader.
Provider discovery checks metadata without constructing another transport. Both roles use the same broker and Flow's existing
`QuarkusManagedExecutorServiceFactory`; no additional private thread pool is introduced.
Explicit transport beans retain Flow's existing selection rules. The boot tests verify the
normal injected broker through successive real workflow waits and verify an explicitly
selected CDI alternative is left intact without an extra local publisher.

This changes the application's default local broker. It does not activate durable inbox
dispatch, transport authentication, governed identity delegation or preview provider features.
It does not make local broadcasts durable, authorize a human wake, add a cross-replica lease,
or close WP-107/T13 or WP-105/T16.

## Relation to issue #75

The interleaving is possible when a finishing instance removes its last listener while a
reauthorized instance enters a listen task for the same event type. It produces the symptom
in [issue #75](https://github.com/bassemZohdy/open-workflow-agentic/issues/75): an instance
remains waiting despite repeated event publication. The old failing run did not retain child
logs, so this is a proven SDK race and an application fix, **not attribution of that historical
failure**. Keep #75 open until its required failure correlation, restart/competing-owner and
strict real-provider checks are complete. Do not increase the existing completion deadline
or weaken the revoked-grant, renewed-authority or credential-persistence assertions.

## Verification

Selected commands and inspected report digests are retained in the accompanying
`evidence/WP-107-local-listener-race/local-verification.json` manifest. Full author CI and
exact tested revision evidence remain required before merge; independent project review
remains deferred by the current delivery agreement.

Revisit the adapter when the SDK version changes. A corrected upstream bucket mutation
should make the raw characterization fail, prompting review and removal of the workaround
after the application and restart tests pass against the replacement.
