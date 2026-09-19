# kitbash-13-recipes-extract-phase0-stack

**Phase** 1 — Recipe engine · **Depends on** `kitbash-12-pipeline-and-deterministic-package` · **Plan** §4, §11, §15, §16

## Goal

The phase-0 stack, reborn as data. This is the task that proves the thesis of the whole
project: a stack is an assembly of recipes, not a checked-in tree.

## Context

§4 requires that recipe content be derived from a reference project that actually compiles,
with placeholders introduced only for names, coordinates and versions. So this is an
extraction, not a rewrite: start from `/reference/spring-boot-java-gradle-layered`, introduce
variables, split the tree along recipe boundaries, and move everything cross-cutting into
patches.

Where the split lands is the real design work. A file that two recipes both want to own is a
sign the boundary is wrong or that the file needs a patch instead of a copy.

## Scope

Recipes under `/recipes`, each with a `recipe.yaml` and a `files/` tree:

- **`base`** — project name, README skeleton, `.gitignore`, `.editorconfig`, licence.
  `provides: [project-root]`.
- **`backend-spring-java`** — `provides: [http-server, rest-api, openapi-spec, jvm-project]`,
  `requires: [build-tool, database]`, `conflictsWith: [backend-spring-kotlin]`. Carries the
  layered architecture file set and the vertical slice.
- **`build-gradle-kts`** — `provides: [build-tool]`.
- **`db-postgres-flyway`** — `provides: [database]`; migration, datasource config, the
  Testcontainers test dependency.
- **`infra-docker`** — Dockerfile and compose with a database healthcheck;
  `provides: [containers]`.
- **`ci-gitlab`** — `provides: [ci]`; a pipeline that builds, tests, lints and produces an
  image (§15).

Rules this task establishes for every future recipe:

- Cross-cutting content is expressed as **patches**, never duplicated files. `.gitignore`
  entries, compose services, dependencies and env vars all arrive via ops.
- The README is **composed from fragments** so it describes only the selected stack — a README
  mentioning something that was not chosen is the tell of a broken generator (§15).
- No recipe knows another recipe's id; they coordinate through capabilities only (§4).

## Out of scope

Maven, Kotlin, hexagonal and modular-monolith layouts, auth, observability — all phase 3. The
architecture option exists in the manifest with `layered` as its only value for now.

## Implementation notes

- Do the extraction file by file with the equality test from `kitbash-19` in mind: the target
  is that rendering these recipes with the reference variable set reproduces the reference
  project byte for byte. Working toward that from the start is far easier than reconciling
  afterwards.
- Resist parameterizing anything that is not a name, a coordinate or a version. Every extra
  variable is a template soup vector.
- Place `// kitbash:` markers deliberately and document each one in the owning recipe's README,
  because another recipe will target it later.

## Files and modules touched

`/recipes/base`, `/recipes/backend-spring-java`, `/recipes/build-gradle-kts`,
`/recipes/db-postgres-flyway`, `/recipes/infra-docker`, `/recipes/ci-gitlab`.

## Done when

- Generating with the reference variable set produces output byte-identical to
  `/reference/spring-boot-java-gradle-layered`.
- The generated project still passes `./gradlew build` in the ecosystem container.
- No recipe references another recipe by id.
