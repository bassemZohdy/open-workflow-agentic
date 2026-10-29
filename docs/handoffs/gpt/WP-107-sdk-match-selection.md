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
