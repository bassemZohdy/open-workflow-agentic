# WP-108 early transaction-resume failure tests

The manual-resolution test fixture injects transaction-manager resume failures after the initial scope lookup and after the decision-retain transaction. At lookup failure, no decision or audit event is retained. At retain failure, the decision is retained but no audit event or applied outcome exists. After explicitly restoring the caller transaction, retrying the same request produces one decision, one audit event and one applied outcome, which remain durable after caller rollback.

The local PostgreSQL 16.15 run of `ManualEffectDecisionStoreTest` passed 19 tests with zero failures, errors or skips. The injected wrapper models a resume exception; it does not establish recovery if the actual transaction manager cannot resume. Deployed datasource and grants, V80 activation, governed integration and T03 remain open. Reciprocal review and GitHub checks are required.
