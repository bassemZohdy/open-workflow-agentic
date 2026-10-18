# ADR-0017: One codebase, two deployable roles, boundaries enforced by the build

**Status:** Proposed  
**Date:** 2026-10-18

## Context

M26 and M27 require separable authority and execution that does not depend on the administration application, but do not require separate deployments or databases (decision D01).

## Decision

Keep one repository with two roles, execution and administration, and two deployables (`app-runtime`, `app-control`) that can run together or apart. ArchUnit forbids runtime-to-control dependencies. Database roles give the runtime read-only access to approved activations and the control application no write access to execution state. The runtime keeps the last approved snapshot with a freshness policy.

### Module layout (as built in WP-001)

| Module | Role |
|---|---|
| `oa-contracts` | Pure Java contracts; JDK only |
| `oa-runtime` | Execution: agents, workflow glue, REST conventions |
| `oa-control` | Administration (skeleton) |
| `oa-eval` | Evaluation suites and gate (skeleton) |
| `oa-testkit` | Test support (skeleton) |
| `app-runtime` | Deployable serving execution |
| `app-control` | Deployable serving administration (skeleton) |

Two rules are enforced by ArchUnit tests in the build:

1. Classes in `..runtime..` do not depend on `..control..` (`oa-runtime`).
2. Classes in `..contracts..` depend only on `java.*` and on each other (`oa-contracts`).

A dependency policy check (`tools/check_dependency_policy.py`) also requires that `oa-contracts` has no main-classpath dependency.

## Consequences

- Separation is enforced by code structure and database privileges rather than by convention.
- Small deployments run both roles in one pod set; large ones split them.
- Extra care is needed for the shared database (privileges, migrations).

## Alternatives considered

- Single module, one application: rejected, boundaries erode.
- Separate repositories and services: rejected, unneeded operating cost (requirement O02 is Optional).
