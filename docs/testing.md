# Testing with oa-testkit

This document explains how acceptance tests are written and recorded, how to run the status generator, and what each test double does. The module is `oa-testkit`; its classes are in `io.github.bassemzohdy.openworkflow.agentic.testkit`. The test strategy is in [plan section 6](plan/README.md#6-test-strategy).

## 1. Writing an acceptance test

Acceptance tests are described in [test-cases.md](test-cases.md) (T01 to T39) and their requirement links are in [requirements.json](requirements/requirements.json). To automate one:

```java
import io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.Acceptance;

@Acceptance(value = "T06", requirements = {"M02"})
class LoopLimitsTest {

    @Test
    void stopsAtTheIterationLimit() {
        // arrange, act, assert
    }
}
```

Rules, enforced when the test runs:

- The id must exist in `requirements.json` (all tests are listed there, even those not in `test-status.json`).
- Each requirement in `requirements` must exist and must be listed under that test's `verifies`. Omitting `requirements` is allowed; declaring one the test does not verify fails the test.
- Put the annotation on a method, or on a class to cover all its methods without their own annotation. Parameterized tests (`@ParameterizedTest`) are rejected, because their report names do not match the recorded method.
- An unknown id fails the test with `unknown acceptance test id 'T999'`. Nothing is recorded for it.

A test is "done" when it is annotated, passes in `mvn -B verify`, and the generator has recorded it (section 2). The annotation alone does not change any status.

Add the test dependency to the module's pom:

```xml
<dependency><groupId>io.github.bassemzohdy.openworkflow</groupId><artifactId>oa-testkit</artifactId><version>${project.version}</version><scope>test</scope></dependency>
```

## 2. Recording status: the generator

The extension cannot write `test-status.json` directly, because the test run may be partial or fail. Instead:

1. The extension writes one marker per annotated method to `<module>/target/acceptance-markers/<class>.tsv`. Surefire 3.5.3 does not put JUnit report entries into its XML, so the markers are the link between the annotation and the report. The same holds for Failsafe: an annotated `*IT` class writes its markers to the same directory of its module (checked with a real `mvn -B verify`; surefire and failsafe both set `basedir` to the module).
2. Surefire writes `<module>/target/surefire-reports/TEST-*.xml` (unit tests) and Failsafe writes `<module>/target/failsafe-reports/TEST-*.xml` (integration tests, `integration-test` and `verify` phases). Both use the same format.
3. `AcceptanceStatus` joins the markers with the XML results of the same module, from both report directories, and decides what changes.

Run the full build first, then the generator from the repository root:

```bash
mvn -B verify
java -cp "oa-testkit/target/classes:$(cat oa-testkit/target/classpath.txt)" \
  io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatus
```

Add `--check` to change nothing and only verify (CI runs this):

```bash
java -cp "oa-testkit/target/classes:$(cat oa-testkit/target/classpath.txt)" \
  io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatus --check
```

`--root DIR` selects another repository root. Exit status: `0` consistent; `1` a regression, or with `--check` a pending change; `2` usage or input error (for example no surefire or failsafe reports: run `mvn -B verify` first).

Rules of the generator:

- A test is **passing** when every observation of it passed. A passing test that is not `automated` is promoted to `automated`, with `where` set to `module:Class#method`. A test that runs in both report sets (the same method in surefire and failsafe) is one observation: it passes only if every testcase passes, and it is recorded once.
- A test recorded as `automated` that **fails, is skipped, or is missing** is a **regression**, whichever report set it is in. Missing means: no marker, the method has no testcase in any report, or the marker's class has no entry in any report (the class did not run, or was skipped as a whole). A failsafe test that is skipped under `-DskipITs` is therefore a regression. The generator never lowers a status automatically and does not write the file when there is a regression.
- The generator writes only `automated`. `planned`, `partial` and `not-started` are set by people, and their vocabulary must match `tools/render_requirements.py` (checked by a unit test). A planned test that fails or is missing is left alone.
- Run the full build. Running a subset with `-Dtest=...` or `-Dit.test=...` makes the other automated tests look missing, which is a regression.

Continuous integration runs `mvn -B verify` (which runs Failsafe in the `verify` phase) and then `--check`, so a failing integration test stops the build before the check, and a skipped or missing one fails the check.

The integration test `ProcessRestartIT` in `app-runtime` is not annotated: no test in [test-cases.md](test-cases.md) describes what it checks (a kill and restart of the packaged application that answers health). Annotate an integration test only when a plan test describes its behaviour truthfully.

## 3. Test doubles

All doubles are plain Java and have unit tests in `oa-testkit`.

### Scripted model (`testkit.model.ScriptedLlmClient`)

Implements the `LlmClient` port of `oa-runtime` ([ADR 0012](adr/0012-reach-models-only-through-an-llmclient-port.md)). Steps are consumed in order, one per call:

```java
var llm = ScriptedLlmClient.create()
        .answer("1. Gather facts\n2. Decide\n3. Report")      // plan
        .delayedAnswer(Duration.ofMillis(200), "draft")        // slow draft
        .fail(new IllegalStateException("provider 503"))       // injected failure
        .timeout(Duration.ofSeconds(1));                       // no answer in time
```

- An empty script fails the call (strict). `fallback(...)` answers instead.
- `calls()` returns every call with its prompt, outcome (`ANSWERED`, `FAILED`, `TIMED_OUT`, `UNSCRIPTED`), elapsed time and token counts. `usage()` totals them.
- `answer(text, inputTokens, outputTokens)` sets token counts explicitly. Without them the counts are estimated at about four characters per token.

### Controllable clock (`testkit.time.ControllableClock`)

A `java.time.Clock` that moves only when told to: `advance(Duration)` (never backwards) and `setInstant(Instant)`. Pass it to code under test that uses time (deadlines, token expiry, timers).

### Process fault injection (`testkit.process.ManagedProcess`)

Starts an application as its own process, with a stable port and a log file, and waits for `/q/health/ready`:

```java
try (ManagedProcess app = ManagedProcess.jar(jar).property("some.key", "value").build()) {
    app.start();
    app.awaitReady(Duration.ofSeconds(120));
    app.kill();          // SIGKILL (destroyForcibly)
    app.restart();       // same port, same configuration
    app.awaitReady(Duration.ofSeconds(120));
}
```

`mainClass(classpath, mainClass)` runs any main class. `ProcessRestartIT` in `app-runtime` is the real use: it kills the packaged `quarkus-run.jar` and starts it again against the local Postgres. It runs in the `integration-test` phase, after `package`.

### Database and network faults (`testkit.fault.FaultProxy`)

A TCP proxy in front of a service (for example Postgres at `localhost:5432`):

- `pause()` / `resume()`: traffic is held, not dropped, so the client sees silence.
- `setDelay(Duration)`: latency per forwarded chunk.
- `cutConnections()`: closes open connections; the proxy keeps listening.
- `stop()` / `start()`: an outage. New connections are refused, then accepted again on the same port.

`FaultProxyPostgresTest` uses it with JDBC: a paused proxy makes a query fail at the socket timeout, and a resumed one works again.

### Fake identity provider (`testkit.idp.FakeIdentityProvider`)

An in-process issuer on `127.0.0.1`:

- `issue(TokenSpec.of(subject, lifetime, scopes...))` signs an RS256 access token. `iat` and `exp` come from the injected clock.
- `GET {jwksUri()}`: the public key as a JWKS.
- `POST {tokenUri()}` with `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`: returns a token whose scope is the requested scope. Widening is refused with `invalid_scope`; an expired or tampered subject token is refused with `invalid_grant`. The new token never outlives the subject token.

Verify tokens with an independent library (the tests use nimbus), not with the issuer.

### Fake policy service (`testkit.policy.FakePolicyService`)

An OPA-shaped decision endpoint: `POST {baseUrl()}/v1/data/<path>` with `{"input": ...}` answers `{"result": <bool>}`.

- Script behaviours in order: `allow()`, `deny()`, `error(status)`, `timeoutAfter(duration)` (answers late, so a caller with a shorter timeout sees a timeout).
- Unscripted requests get the default, which is **deny** (fail closed); change it with `defaultTo(...)`.
- `requests()` records each path and input for assertions.

## 4. Known limits

- **Markers and stale output.** Markers are kept in `target/` and replaced per method. A removed test leaves its marker until `mvn clean`, and the generator then reports the missing testcase as a regression, which is the correct outcome.
- **Parameterized and nested tests.** `@Acceptance` is supported on plain JUnit 5 methods and classes only.
- **Process faults are Linux and macOS.** `destroyForcibly` sends SIGKILL there. Ports are chosen free before start, which has a small race.
- **Token counts are not from the provider.** The `LlmClient` port returns text only; the double reports counts from the script or a character heuristic. Real token accounting needs a richer port (a later work package).
- **The proxy is TCP-level.** It cannot inject protocol errors, partial writes inside a statement, or TLS failures.
- **The fake IdP is not a security product.** Keys are generated per instance; there is no refresh token, no `act` claim, no persistence, and no discovery document. It exists to test clients against a real JWT and exchange shape.
- **The fake policy service is one API shape.** It does not evaluate rules; it returns scripted decisions.
- **The Postgres test needs the local database.** `FaultProxyPostgresTest` and `ProcessRestartIT` use Postgres on `localhost:5432` (user and password `postgres`), as the other module tests do. CI provides it as a service.
- **Test IDs are validated against `requirements.json`, not `test-status.json`.** The latter lists only tests whose status is not `not-started`.
