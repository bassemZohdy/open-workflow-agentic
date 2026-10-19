# ADR-0024: Group applications and library modules

**Status:** Accepted

**Date:** 2026-10-19

## Context

Seven top-level Maven module folders obscure the difference between applications and libraries.
The root README still describes an early bootstrap state. Standalone spike implementations also obscure
which code is maintained product code. The module responsibilities themselves remain useful.

## Decision

1. Put the two application modules under `apps/` and five library/test modules under `modules/`.
2. Keep Maven artifact IDs, Java packages, capability boundaries and dependencies unchanged.
3. Keep a single root parent/reactor POM, listing grouped module paths directly. Group directories have no POM.
4. Keep Quarkus application packaging in runnable applications and Jandex indexing in CDI-bearing libraries.
5. Keep unit tests beside their libraries and boot/process tests in the application. Update acceptance evidence
   paths and dependency checks to support grouped module locations.
6. Retain spike outcomes and decisions in documentation; remove experimental projects. Product behavior and
   regression tests belong in the responsible product module.
7. Preserve the distinction between implemented execution and the planned control/evaluation skeletons.

This is a repository navigation decision. Quarkus does not prescribe the names `apps` and `modules`.
The service boundary remains the application role, not each Maven module or feature package.

## Consequences

- Root build commands remain valid; direct application commands now use `apps/app-runtime`.
- Each child POM points to `../../pom.xml`. No additional Maven aggregation layer is introduced.
- Acceptance evidence records grouped paths; dependency-policy checks use each module directory's leaf name.
- CI continues to enforce contracts purity, execution/control separation, test-support scope and licence rules.
- This change does not implement the administration app, new deployment services or deferred governance controls.

## Alternatives considered

- Keep all seven modules at the root: valid in Maven and Quarkus, but less clear for this project's users.
- Merge everything into one module: loses existing build boundaries.
- Split every module into a service: adds deployment overhead without a requirement for those boundaries.

## References

- [Repository structure and conventions](../repository-structure.md).
- [Quarkus multi-module projects](https://quarkus.io/guides/maven-tooling/#working-with-multi-module-projects).
- [Quarkus application and library packaging](https://quarkus.io/guides/quarkus-maven-plugin/#lifecycle).
