# Repository structure

## Deployment model

This is a Maven multi-module repository with one runnable Quarkus execution application today.
The administration application is a skeleton for later work. Five library/test modules support the two
application roles; they do not run as services.

Grouping modules under `apps/` and `modules/` is a navigation choice for this repository, not a Quarkus requirement.
Maven artifact IDs and Java package names remain unchanged. ADR [0024](adr/0024-group-applications-and-library-modules.md)
records the folder decision; ADR [0017](adr/0017-one-codebase-two-deployable-roles-with-enforced-boundaries.md)
records the deployment and authority boundaries.

## Where code belongs

| Path | Owns | Dependencies |
|---|---|---|
| `apps/app-runtime` | Application configuration, workflow definitions, startup wiring, Quarkus boot tests and process tests | `oa-runtime`; `oa-testkit` in test scope |
| `apps/app-control` | Future administration application wiring | `oa-control` |
| `modules/oa-contracts` | Framework-free contracts, JSON schemas, identifiers and ports | JDK only on its main classpath |
| `modules/oa-runtime` | Execution implementation, feature packages, policy, audit, persistence adapters and migrations | `oa-contracts` and framework libraries; no control dependency |
| `modules/oa-control` | Future governance registry, publication and approval implementation | `oa-contracts` |
| `modules/oa-eval` | Future evaluation suites and gate implementation | `oa-contracts` |
| `modules/oa-testkit` | Acceptance annotation/reporter, scripted models and fault injection | `oa-contracts`, `oa-runtime`; consumed only in test scope |
| `docs/` | Architecture, decisions, requirements, implementation evidence and spike outcomes | Generated pages come from the JSON sources |
| `tools/` | Documentation generation and dependency-policy checks | Python standard library |

Keep the runtime organized by capability (`agent`, `api`, `audit`, `lifecycle`, `operations`, `outbox`, `policy`,
`identity`, `schedule`). Keep internal types beside their feature rather than introducing repository-wide controller,
service and repository packages. Extract another library only when it has a concrete ownership or reuse need.
Do not create placeholder services for each feature.

## Quarkus and Maven conventions

- The repository root is the parent and reactor POM. It lists the seven module paths directly; grouping
  directories need no additional parent POMs. Child POMs resolve the shared parent with `../../pom.xml`.
- Only a runnable application configures the Quarkus Maven build plugin. Library modules remain ordinary JARs.
  `app-control` gains its Quarkus plugin when its application is implemented.
- `oa-runtime` generates a Jandex index so Quarkus discovers its CDI beans and REST resources. Apply the same
  indexing convention when adding CDI beans to another library consumed by an application.
- The root manages dependency versions through the existing BOMs. This folder migration does not upgrade frameworks.
- Test support is a test-scoped dependency of applications. Library unit tests stay in their own module;
  `@QuarkusTest` and process-level `*IT` tests run in the application module. Failsafe runs the process tests after packaging.
- Application configuration selects infrastructure and model bindings. Migrations stay with the library that owns
  the schema; application startup applies the selected migrations.
- Use Maven artifact selectors when selecting a module from the root, for example
  `mvn -B -pl :app-runtime -am verify`. To run dev mode, first install dependencies from the root, then run
  `mvn quarkus:dev` in `apps/app-runtime`.

## Boundaries checked in CI

ArchUnit checks contracts purity and the runtime-to-control code dependency boundary.
`tools/check_dependency_policy.py` also checks resolved dependencies, including transitive dependencies:

1. `oa-contracts` has no main-classpath dependency, including dependencies on other project artifacts.
2. `oa-runtime`, `app-runtime` and `oa-testkit` do not depend on control artifacts.
3. `oa-testkit` can be consumed only in test scope.
4. Application artifacts cannot be consumed as libraries.
5. Dependency licences satisfy the scope policy.

The acceptance reporter discovers report directories in flat or grouped module layouts and records
repository-relative paths in `docs/requirements/test-status.json`. Existing automated status is retained;
folder moves only change evidence locations. Run a clean build after moving module directories to avoid
reading stale reports from the previous layout.

## What remains planned

`app-control`, `oa-control` and `oa-eval` are explicitly marked skeletons. Separate deployment, database roles,
approved-state snapshots, governance publication and evaluation gates remain work-package deliverables.
Folder separation alone does not implement those controls. Development/authoring is a logical responsibility;
it does not currently require an additional service.

## References

Reviewed on 2026-10-19:

- [Quarkus: multi-module projects and CDI indexing](https://quarkus.io/guides/maven-tooling/#working-with-multi-module-projects).
- [Quarkus Maven plugin: application and library packaging](https://quarkus.io/guides/quarkus-maven-plugin/#lifecycle).
- [Quarkus: application testing](https://quarkus.io/guides/getting-started-testing/).
