# WP-107 SDK match-set preparation

Owner: GPT. Reviewer: Claude. Status: opt-in data preparation; WP-107 and T13 remain partial.

`SdkListenMatchSet` evaluates a complete bounded set of SDK type registrations for one retained CloudEvent. It
returns every matching logical slot before `InboxListenGroup.classify` or `JdbcListenGroups.offer` chooses a
disposition. Duplicate `all` filters stay distinct; several physical wildcard registrations may share the one
logical wildcard slot. The SDK main predicate uses the registered workflow and task context. Any correlation predicate is
refused at construction, before predicate evaluation, until its state can be bound to durable selection. Type mismatch skips predicate evaluation. Missing or foreign slots, different
task contexts, excessive registrations/correlations, malformed receipts and predicate failures refuse the selection.
No registration callback is invoked and no database connection is opened by the selector.

The trusted coordinator still must derive the binding between SDK registrations and the pinned configuration
snapshot, validate deployment-approved routes and current authority, bound predicate execution time, and pass the
complete set to the owned group store. A match set is data, not a lease, reservation, receipt proof, callback
permission or continuation fence. The selector is not CDI-wired and cannot deliver events or complete T13.

Focused local verification: `mvn -pl modules/oa-runtime -am test
-Dtest=SdkListenMatchSetTest,SdkEventRegistrationSemanticsTest -Dsurefire.failIfNoSpecifiedTests=false
-Dcheckstyle.skip=true`: nine tests, zero failures/errors/skips. Exact peer review and GitHub CI are required.

Review remains open: registration-to-snapshot binding and full pre-classification replay identity are not yet enforced. The selector must not be connected to production delivery.

## Open review findings and next design boundary

Claude re-reviewed `a7e392a` and requested changes. The focused tests passed, but they do not establish the pinned-registration binding or replay safety. Refusing every correlation predicate avoids invoking the known first-value correlation mutation; it does not establish that the main predicate is pure. A caller must treat this refusal as terminal and must not fall back to unfiltered delivery.

The SDK exposes `EventRegistrationBuilderInfo.from` to derive registrations from the listen task using the application's configured event consumer. That is a possible construction path, not a verification mechanism by itself: it still needs to bind the task and the configured consumer to the retained snapshot and approved routes, and handle wildcard registrations. Caller-supplied predicate objects cannot be authenticated by checking only their slot labels. Any expression or custom consumer may require stronger isolation before speculative evaluation.

The group store currently records classified memberships rather than the complete data-and-until match set. Replay can therefore accept a changed complete set when the classification is unchanged. Correcting that requires retained complete-set identity and a migration strategy for existing immutable history. A token or hash supplied by an unchecked caller cannot prove that no matching slot was omitted. Until these boundaries and rejected-offer/lease-loss behavior are established, this draft must not be wired to delivery or merged as a completed coordinator.
