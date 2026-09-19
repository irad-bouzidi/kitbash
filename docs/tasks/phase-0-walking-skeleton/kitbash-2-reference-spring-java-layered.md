# kitbash-2-reference-spring-java-layered

**Phase** 0 — Walking skeleton · **Depends on** `kitbash-1-repo-scaffold` · **Plan** §4, §15, §18

## Goal

The first reference project: a real, compiling, test-passing Spring Boot service that a
developer would be happy to inherit. It is the source of truth that recipe content is later
derived from, never the other way round.

## Context

§4 is explicit — recipe content is not written from scratch, it is extracted from a project
that actually compiles, with placeholders introduced only for names, coordinates and
versions. Editing a real project beats editing template soup. This task produces the project;
`kitbash-13` extracts it; `kitbash-19` binds the two with a CI equality test.

The acceptance bar is §15 in full. Every bullet there is a bullet here, because if the
reference project does not clear the bar, nothing generated from it ever will.

## Scope

`/reference/spring-boot-java-gradle-layered/` containing:

- **Layered package layout** — `controller/`, `service/`, `repository/`, `domain/`.
- **A working vertical slice** — one example entity with a Flyway migration, a Spring Data
  repository, a service, a REST controller and a springdoc-documented endpoint. An empty
  skeleton teaches nothing; a working slice is something to edit.
- **Passing tests out of the box** — one unit test and one Testcontainers integration test
  against a real Postgres.
- **Containers** — multi-stage, non-root `Dockerfile`; `compose.yaml` with a database
  healthcheck so the app does not race the database on startup.
- **Configuration** — `.env.example` listing every variable the app reads, and fail-fast
  startup with a readable message when one is missing.
- **Operations** — Actuator health endpoint, structured JSON logging, request correlation ids.
- **Quality gates** — Spotless, `.editorconfig`, a format check wired into the project's own
  build.
- **A `.gitignore`** correct for exactly this toolchain.
- **A README** whose first screen of text states the single start command and describes only
  this stack.

## Out of scope

No templating, no placeholders, no `.peb` files. This is an ordinary project that happens to
live in `/reference`. Frontend, auth and observability recipes get their own reference
projects later.

## Implementation notes

- Choose the names that will become variables (`com.example`, `demo`, the entity name) and
  keep them consistent and distinctive, so the extraction in `kitbash-13` is a mechanical
  find-and-replace rather than a hunt.
- Record that variable set in `reference-variables.json` beside the project now — `kitbash-19`
  needs exactly this file.
- The project must build standalone: it is not a Gradle module of `/server` and must not
  inherit anything from the root build.

## Files and modules touched

`/reference/spring-boot-java-gradle-layered/**`, `/reference/README.md`.

## Done when

- From a clean checkout of only that directory, `./gradlew build` passes, including the
  Testcontainers integration test.
- `docker compose up` brings the service up healthy, and the command printed in the README's
  first screen is the command that works.
- Every §15 bullet applicable to a backend-only project is satisfiable by pointing at a file.
