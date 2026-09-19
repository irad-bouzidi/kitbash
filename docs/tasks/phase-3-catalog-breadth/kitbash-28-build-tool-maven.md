# kitbash-28-build-tool-maven

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-13-recipes-extract-phase0-stack` · **Plan** §11, §18

## Goal

The second build tool, and with it the first real test of whether `addDependency` is a
format-aware operation or a Gradle-shaped one wearing a general name.

## Context

§18 explains why Maven was deliberately deferred out of phase 1: *it doubles every JVM
backend's dependency patches, and phase 1 does not need that weight.* Now that the patch engine
is proven on one format, adding the second is the cheapest available proof that the abstraction
holds.

If this task turns into special-casing inside the applier, that is worth surfacing — it means
`addDependency` needs a proper per-format strategy rather than a branch.

## Scope

- **Reference project** `/reference/spring-boot-java-maven-layered/` — real, compiling, with the
  same vertical slice and tests as the Gradle reference.
- **Recipe** `/recipes/build-maven` — `provides: [build-tool]`,
  `conflictsWith: [build-gradle-kts]`.
- `addDependency` proven against `pom.xml` at the same quality as `build.gradle.kts`: right
  section, dedupe, dependency-management aware, formatting preserved.
- Build-tool-dependent fragments in the CI, Docker and README recipes selected via `when`
  expressions — **not** via a branch in `core`.
- The version catalog hook (`kitbash-11`) either applies to Maven through
  `dependencyManagement` or explicitly does not apply, with the choice documented.
- Matrix cells for both build tools.
- The reference equality test extended to the new reference project.

## Out of scope

Bazel and Mill stay deferred (§11).

## Implementation notes

- `pom.xml` editing must go through a real XML tree with formatting preservation. A regex-based
  `pom.xml` patch is exactly the "broken syntax within a week" failure §4 warns about.
- The Maven wrapper (`mvnw`) needs mode 0755 in the packager, same as `gradlew` — check the
  post-process stage handles it rather than assuming.

## Files and modules touched

`/reference/spring-boot-java-maven-layered/**`, `/recipes/build-maven/**`,
`/server/core/**` (Maven applier strategy), `/verification/cells/**`.

## Done when

- Both build tools generate projects that build green in the matrix.
- The `addDependency` idempotency and dedupe tests pass identically for Maven and Gradle.
- No technology name was added to `core` outside the applier's format strategy.
