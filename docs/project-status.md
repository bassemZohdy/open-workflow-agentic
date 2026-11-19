# Project status — 2026-11-19

The project remains in active implementation. The initial inventory used baseline
`403fce08ab84b2eb637231b2c6c3393d33e9014d`, which matched local and remote main at the start of this check.
The recovery/status increment subsequently merged as `d476aa8d379ce3766b2d5c64ff1dea4e62ee7ea1`
after a full CI pass. The injected Flow validation proof then merged on 2026-11-20 (Dubai)
as `1a8716d320284d9849adf777ee24911968323b26` after its full CI pass.
The following increment corrects a deterministic local-listener registration race; its author checks
and remaining verification limits are recorded separately.

| Tracking source | Current result |
| --- | --- |
| Work packages | 53 total: 11 completed, 17 partial, 25 not started |
| Acceptance scenarios | 3 of 39 automated; no status promoted in this change |
| Applications | `app-runtime` runnable; `app-control` remains a skeleton |
| Pull requests at the start of the check | None open; PR #99 is merged |

The authoritative backlog remains [TODO.md](../TODO.md), generated from
[work-packages.json](plan/work-packages.json). Implemented components and passing component tests do not
complete packages whose production integration and acceptance gates remain open.

## Completed during this status check

- Added [research outcome crash recovery](handoffs/gpt/WP-108-research-outcome-recovery.md): an actual
  graph worker completes three reserved model calls and halts before outcome association. Fresh workers
  recover the same historical outcome after cancellation, survive a second halt, and refuse changed
  checkpoint bytes. Counts remain one entry, one outcome and three provider calls.
- Refreshed the plan's review date/baseline and replaced the obsolete open-PR summary with the checked
  repository state. Preserved every package and acceptance status.
- Aligned the contributor guide with the existing delivery agreement: active implementation may cross
  domain areas, required author checks remain mandatory, and independent project review follows implementation.
- Corrected portability documentation that claimed absent injected application tests and linked missing
  handoffs. Retained the standalone CTK evidence and made the remaining application checks explicit.
- Removed the ignored Flow input-validation setting and its unsupported enforcement claim. This
  configuration cleanup does not establish the missing application-level validation behavior.
- Fixed an inbox test's reused engine ID, which caused repeated test runs on the same development
  database to fail before reaching the engine-mismatch assertion.
- Added actual injected Flow checks for URI components and explicit/default JSON schemas, including
  inline/external resources and transformation order. The checked import refuses unknown formats before
  resource access; the raw SDK's JSON fallback remains a measured gap. See the
  [runtime proof](handoffs/gpt/WP-605-flow-runtime-proof.md).
- Corrected a default local-broker race that could detach a new listener while the last old listener
  of the same event type was removed. The raw SDK ordering is reproduced deterministically and the
  replacement keeps the new listener reachable. Configured transports retain their selection path;
  this local broadcast fix does not complete durable governed event delivery. See the
  [listener-race handoff](handoffs/gpt/WP-107-local-listener-race.md).

## Verification

| Check | Result |
| --- | --- |
| Generated requirement and progress documents | Pass |
| Python tooling suite | 91 tests pass |
| Development compose validation | Pass |
| Native Windows process-family cleanup | Four cases pass, including live/exited leaders and unrelated sentinels |
| New recovery IT, Windows Java 25 / PostgreSQL 16.15 | One test, zero failures/errors/skips; compilation targets Java 21 |
| New recovery IT, Linux Java 21 / PostgreSQL 16.15 | One test, zero failures/errors/skips |
| Linux contract/runtime/test-support suites | 1,413 tests pass; zero failures/errors/skips |
| Linux application unit sweep | 905 tests; one reused-ID fixture error, corrected and verified separately; no other failures/errors/skips |
| Corrected inbox suite, same database | 14 tests pass on each of two successive runs |
| Default Linux packaged-process suite | 19 tests pass; zero failures/errors/skips |
| Package after ignored-setting cleanup | Pass; no unrecognized configuration warnings |
| Acceptance status consistency | 12 annotated methods pass; all three recorded automated scenarios remain consistent |
| Dependency boundaries and license policy | Pass |
| Full CI for PR #146 | 2,337 tests in 286 XML suites; zero failures/errors/skips; merged tree equals tested tree |
| Injected Flow validation proof | Three tests, seven measured cases; zero failures/errors/skips on Linux Java 21 |
| New workflow fixture | Valid against pinned development 1.0.3 schema; no validation errors or lint warnings |
| Full CI for PR #147 | 2,340 tests in 287 XML suites; zero failures/errors/skips; merged tree equals tested tree |
| Final local-broker checks, Linux Java 21 | 15 selected tests pass; five compiled source copies match after line-ending normalization |
| Packaged identity and lifecycle recovery | Seven tests pass before the final transport-provider discovery guard; final boot checks rerun afterward |

The native Windows full build stopped in `oa-runtime`: 45 errors arise from the unavailable Cedar native
binding or its failed class initialization, and two filesystem tests cannot create symbolic links with the
current Windows privileges. The new standalone recovery test passes on Windows. These results do not
establish that the full application suite supports native Windows; Linux remains the CI verification environment.

The selected recovery command is in its handoff. The broad `mvn -B clean verify` run exited with the
single inbox fixture error. The corrected suite passed twice on the same database, and the default
packaged-process suite completed separately. The subsequent package build verified removal of the
ignored configuration setting. Those local author checks combined several runs. The subsequent
[full CI run](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/44440610397) passed
the complete reactor before PR #146 merged. Its [retained proof](handoffs/gpt/evidence/WP-108-research-outcome-recovery/ci-verification.json)
includes the exact checked tree and parsed report totals. The Flow validation increment also passed
its [full CI build](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/44541334921)
before merge; its [retained evidence](handoffs/gpt/evidence/WP-605-flow-runtime-proof/ci-verification.json)
records the parsed reports and tree match. The local-listener correction has passed selected author checks;
its own full combined-tree CI remains required. Its
[manifest](handoffs/gpt/evidence/WP-107-local-listener-race/local-verification.json) preserves the packaged-run
source difference and does not claim the historical intermittent identity failure is explained or closed.

The [verification manifest](handoffs/gpt/evidence/WP-108-research-outcome-recovery/local-verification.json)
records commands, counts, source and log digests, the original local failure and verification limits. Original
XML and process logs remain under `target/project-status-linux*`. The opt-in real-Keycloak profiles
were not executed: their two classes report zero tests in the default process run. No provider proof
or production-readiness claim follows from the passing default suite.

## Remaining delivery gates

Continue WP-108's governed adapter and effect-aware Flow recovery, including production authority,
approved target/provider sources, effective database grants, current output authorization and end-to-end
failure tests. The new recovery proof reconstructs historical facts with fixture authority; it does not
activate an automatic recovery dispatcher or authorize private output release.

The open repository issues remain:

- [#75: intermittent identity recovery](https://github.com/bassemZohdy/open-workflow-agentic/issues/75):
  isolate the causal race and add deterministic regression evidence. A passing rerun does not close it.
- [#76: supported-only production identity profile](https://github.com/bassemZohdy/open-workflow-agentic/issues/76):
  the owner selected supported features only; implementation, threat-model and live Flow proof remain open.
- [#26: framework upgrade assessment](https://github.com/bassemZohdy/open-workflow-agentic/issues/26):
  retain the chosen dependency baseline until a separate compatibility assessment justifies a change.

The rest of the 42 unfinished packages, their dependencies and decisions remain visible in the generated
backlog. No deployment, production identity-profile approval or independent final project review is claimed.
