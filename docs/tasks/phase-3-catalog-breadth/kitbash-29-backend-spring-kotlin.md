# kitbash-29-backend-spring-kotlin

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-28-build-tool-maven` · **Plan** §11, §19

## Goal

A second backend, proving the backend slot is a slot and not a special case — and providing the
cleanest possible evidence for the §19 working principle.

## Context

The interesting output of this task is its diff. §19 says adding a technology should mean adding
a recipe directory plus, rarely, one hook — never editing the core generation workflow. This is
the first task where that claim is testable against a genuinely different language, with its own
build plugin, its own linter and its own reserved words.

Note the irony the plan already flags (§2): the server itself is Java-only, and Kotlin survives
in this project only in Gradle build scripts and in what the catalog emits.

## Scope

- **Reference project** `/reference/spring-boot-kotlin-gradle-layered/` — same vertical slice,
  same tests, same §15 bar as the Java reference.
- **Recipe** `/recipes/backend-spring-kotlin` — the same `provides` set as the Java backend
  (`http-server`, `rest-api`, `openapi-spec`, `jvm-project`),
  `conflictsWith: [backend-spring-java]`.
- Kotlin plugin wiring for both build tools, via the existing dependency patches.
- **ktlint** wired into the generated project, with a format check in its CI (§15).
- Kotlin reserved words already rejected by `kitbash-20`; confirm coverage with tests against
  the Kotlin-specific set.
- Matrix cells: Kotlin × Gradle and Kotlin × Maven.
- Reference equality test extended.

## Out of scope

Kotlin-specific frameworks (Exposed, Ktor) and Kotlin coroutine idioms beyond what the reference
project needs. Quarkus, NestJS, FastAPI and Go remain deferred (§11).

## Implementation notes

- Resist sharing template files between the Java and Kotlin backends through clever conditionals.
  Two reference projects and two recipe trees are honest; one templated tree that renders both
  languages is template soup, which is precisely what §4 forbids.
- Shared *patches* are fine and expected — that is what the patch layer is for.

## Files and modules touched

`/reference/spring-boot-kotlin-gradle-layered/**`, `/recipes/backend-spring-kotlin/**`,
`/verification/cells/**`.

## Done when

- Kotlin × both build tools generate and build green.
- **The MR diff touches only** `/recipes`, `/reference`, `/verification` and docs — stated in the
  MR description and visible in the diff. If it does not, the deviation is explained there.
