# WP-108 research outcome recovery

Prepared 2026-11-19 against implementation baseline `403fce08ab84b2eb637231b2c6c3393d33e9014d`.
WP-108 remains partial; acceptance status is unchanged.

`ResearchOutcomeRecoveryIT` executes the real fixed research graph and PostgreSQL checkpoint/reservation
stores in a child JVM. Its provider writes a separate committed attempt row for every reserved model call.
The test halts that JVM after terminal graph completion, before any outcome association, then cancels the
registry instance. A fresh JVM reconstructs the original entry from retained binding data, associates its
released terminal checkpoint and halts before reporting success to the parent. Two more fresh recovery
processes verify the same association.

Assertions require one irreversible entry, one outcome, one entry-audit event, three model reservations and
exactly three provider attempts. Changing the terminal checkpoint to different valid stored bytes makes
both verification and reassociation refuse in another process; the original association stays unchanged.
Recovery phases do not construct a graph or provider and never deserialize checkpoint content. They
recover historical facts, which remain distinct from permission to enter or release private output.

The worker uses a plain PostgreSQL datasource and a fixture transaction manager with no ambient JTA.
The graph's caller, policy, model approval and provider are fixtures. Proposed V78/V79 are applied only to
the disposable test database. No live issuer, production transport, deployed roles, CDI/HTTP route,
automatic Flow recovery or private output release is established. This does not resolve issues #75/#76,
complete WP-108, or expand T03 acceptance coverage. Real JTA suspension retains its separate boot tests.

Run from the repository root:

```powershell
mvn -B -pl apps/app-runtime -am verify '-Dtest=NoUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=ResearchOutcomeRecoveryIT' '-Dfailsafe.failIfNoSpecifiedTests=false'
```

The first Windows run could not start a child because the application classpath exceeded the operating
system's command-line limit. The launcher now uses a temporary Java argument file, cleans it after the
child exits, and forcibly terminates a timed-out child. The corrected Windows Java 25/PostgreSQL 16 run
passed one integration test with zero failures, errors or skips. Java compilation targets release 21.
The same selected integration test passed on Linux Java 21/PostgreSQL 16.15 with zero failures, errors or skips.
Per-process logs and Failsafe XML are retained under the application's `target/` directories and by the
existing full CI workflow. The
[verification manifest](evidence/WP-108-research-outcome-recovery/local-verification.json) records the
passing default process suite, the separate unit-sweep fixture correction, source digests and limits.
The subsequent [full CI verification](evidence/WP-108-research-outcome-recovery/ci-verification.json)
passed 2,337 tests across 286 XML suites with zero failures/errors/skips, including this recovery IT.
PR #146 merged as `d476aa8d379ce3766b2d5c64ff1dea4e62ee7ea1`; its tree exactly matches the CI checkout.
This closes that increment's pre-merge CI gate without changing package acceptance or the deferred review.
