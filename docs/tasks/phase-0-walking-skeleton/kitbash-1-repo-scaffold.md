# kitbash-1-repo-scaffold

**Phase** 0 — Walking skeleton · **Depends on** — · **Plan** §5, §16

## Goal

The repository shape from §16 exists, builds empty, and enforces the two structural rules
that are painful to retrofit: `core` has no framework dependencies, and the JVM toolchain
is pinned.

## Context

Everything later assumes a Gradle multi-module server whose domain layer can be driven from
tests and the CLI without booting a Spring context (§6). Declaring all six modules now —
even empty — means no later task has to reshape the build while also writing logic.

## Scope

- `git init`, `main` as the default branch, first commit.
- `/server` — Gradle 8.x, Kotlin DSL, version catalog in `gradle/libs.versions.toml`.
  Modules declared now, empty is fine: `core`, `catalog`, `render`, `api`, `verify`, `cli`.
- Module dependency rules encoded in the build, not in a wiki:
  - `core` → stdlib only. No Spring, no Lombok (§5).
  - `catalog`, `render` → `core`.
  - `api` → `core`, `catalog`, `render`, Spring Boot.
  - `verify`, `cli` → `core`, `catalog`, `render`.
- Java 21 toolchain pinned via Gradle toolchains so the build does not depend on the
  developer's `JAVA_HOME`.
- Spotless with a Java format config, plus `.editorconfig` at the repo root.
- Directories with placeholder READMEs stating what belongs in them:
  `/web`, `/recipes`, `/reference`, `/verification`, `/docs`.
- Root `compose.yaml` with `postgres` and `minio` services (server and web are added in
  later tasks).
- `.gitignore` covering Gradle, Node, IDE and OS noise.
- `.gitlab-ci.yml` with one `build` job running `./gradlew check`.
- `docs/adr/` started, with the first ADR recording the choices §2 already decided
  (Java 21, Gradle Kotlin DSL, Pebble, in-memory workspace) so future contributors find the
  reasoning without reading the whole plan.

## Out of scope

No application code, no Spring Boot application class, no recipes. A module that contains
nothing is the correct output of this task.

## Implementation notes

- Enforce the `core` purity rule with a test or a Gradle verification task that inspects the
  runtime classpath and fails on a `org.springframework` or `lombok` coordinate. A convention
  everybody agrees to is not enforcement.
- Put shared build logic in `buildSrc` or a convention plugin from the start; six modules
  each with a copy-pasted `java { }` block rots immediately.

## Files and modules touched

`/server/**`, `/gradle/libs.versions.toml`, `/.gitlab-ci.yml`, `/compose.yaml`,
`/.editorconfig`, `/.gitignore`, `/docs/adr/0001-*.md`, placeholder READMEs.

## Done when

- `./gradlew check` passes on a clean clone, locally and in CI.
- `./gradlew :core:dependencies` shows no Spring and no Lombok, and the guard task fails if
  someone adds one — demonstrated by a reverted commit in the MR discussion.
