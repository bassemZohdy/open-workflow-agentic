# ADR-0017: One codebase, two deployable roles, boundaries enforced by the build

**Status:** Proposed  
**Date:** 2026-10-18

## Context

M26 and M27 require separable authority and execution that does not depend on the administration application, but do not require separate deployments or databases (decision D01).

## Decision

Keep one repository with modules `oa-runtime` and `oa-control` and two deployables that can run together or apart. ArchUnit forbids runtime-to-control dependencies. Database roles give the runtime read-only access to approved activations and the control application no write access to execution state. The runtime keeps the last approved snapshot with a freshness policy.

## Consequences

- Separation is enforced by code structure and database privileges rather than by convention.
- Small deployments run both roles in one pod set; large ones split them.
- Extra care is needed for the shared database (privileges, migrations).

## Alternatives considered

- Single module, one application: rejected, boundaries erode.
- Separate repositories and services: rejected, unneeded operating cost (requirement O02 is Optional).
