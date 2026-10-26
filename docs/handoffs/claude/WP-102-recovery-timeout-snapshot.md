# WP-102: IdentityRecoveryIT timeout diagnostic snapshot (follow-up to merged GPT PR90, main 99631cc)

Status: **diagnostics only. No behavioural fix; no cause claim for issue75.** The unresolved packaged identity recovery
failure stays unresolved, and a snapshot is not evidence of the cause.

## What changed (test sources only, nothing under src/main, no GPT resource/build/contract/tracking file, no GPT handoff)

- `apps/app-runtime/src/test/java/io/github/bassemzohdy/openworkflow/agentic/identity/RecoveryEngineSnapshot.java` (new,
  package-private): one `GET /dev/lifecycle/engine?definition=agentic/lifecycle-probe&version=0.1.0&instanceId=<engine id>`
  with an independent 2 s deadline over the whole exchange (async send, bounded `get`, then `cancel(true)` and
  `HttpClient.shutdownNow()`; never a blocking `close()`; interrupt flag preserved). Returns
  `engine snapshot: present=<bool> status=<NAME>` only for an HTTP 200 with a boolean `present` and a consistent status:
  `present=true` needs an exact `EngineStatus` name, `present=false` needs exactly `ABSENT`. Bodies declaring over 4096
  bytes are dropped. Everything else (404, 503, other status, malformed
  JSON, wrong types, odd status text, timeout, refused connection, bad URL) returns the fixed marker
  `engine snapshot unavailable`. It never throws and copies no body or exception text. `ABSENT` is reported as reported and
  never interpreted as completed.
- `IdentityRecoveryIT.java`: `decideUntilCompleted` takes the already-known engine id (engineX / engineY) and its failure
  message now carries the snapshot. The message reuses the registry status the existing loop last observed, so the failure
  path makes no extra registry HTTP request. Unchanged: the 90 s completion loop, authority/secret/correlation assertions,
  broadcast event type and order, and the IT stays enabled for the required Linux full CI.
- `RecoveryEngineSnapshotTest.java` (8 tests, real `com.sun.net.httpserver.HttpServer` on loopback): exact request
  line and a real WAITING answer; ABSENT not read as COMPLETED; 503 / 404 / non-JSON / wrong type / private status text /
  missing field / empty body all give the fixed marker with no canary or exception text; uppercase canary / unknown status,
  `present=true`+`ABSENT` and `present=false`+`COMPLETED`/`WAITING` are rejected; every real `EngineStatus` is accepted; a
  header-stalled endpoint and a headers-immediate/body-stalled endpoint are abandoned within the deadline; an interrupted
  caller gets the marker promptly and keeps its interrupt flag; refused connection and an unusable base URL return the marker without throwing.

## Commands and results

Java 21.0.12.1 (`C:\Users\Bassem\AppData\Local\Codex\runtimes\java21\jdk-21.0.12.1+1`), Maven 3.9.11
(`C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.11\bin\mvn.cmd`):

    mvn -B -q -o -pl apps/app-runtime -am test -Dtest=RecoveryEngineSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false

Result: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`. Observed before the fix (same new tests against the previous
helper): the delayed-body test failed at 5006 ms (`HttpRequest.timeout` did not bound the body) and `PRIVATECANARY` was
echoed as a status. After the fix both pass. This also compiled all app-runtime test sources, including the
modified `IdentityRecoveryIT`.

## Limits

- `IdentityRecoveryIT` was **not run** here (no full or native process run on Windows); it needs the packaged jar and
  Postgres and runs in Linux CI. The failure-message path is therefore proven only through the helper's unit tests, not by
  forcing a real 90 s timeout.
- The snapshot is taken once, after the timeout, so it shows the state at that moment, not how it got there. The listener
  failure/restore and terminal-report-loss hypotheses are not investigated by this change and still need deterministic proof.
- Required Linux CI and GPT/maintainer review remain pending. Nothing was pushed and no PR was opened.
