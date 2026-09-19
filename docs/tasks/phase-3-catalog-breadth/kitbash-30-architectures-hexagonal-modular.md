# kitbash-30-architectures-hexagonal-modular

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-29-backend-spring-kotlin` · **Plan** §2, §11

## Goal

Two more architecture options that are structural rather than cosmetic, and a mechanism that
keeps them that way.

## Context

§11 sets the bar and the consequence: *if an architecture option only renames folders, drop it —
a misleading option is worse than a missing one.* It is also why Clean is not in v1: as usually
implemented it emits the same tree as Hexagonal (§2).

An architecture that is only a folder layout decays the first time someone edits the reference
project without thinking about direction of dependency. So each architecture ships with an
enforcement test **inside the generated project**, which turns the architectural claim into
something the user's own build checks.

## Scope

- **Hexagonal** — `domain/`, `application/port/`, `adapter/in/web/`, `adapter/out/persistence/`,
  with a **framework-annotation-free domain**.
- **Modular monolith** — per-feature packages with internal layering and an explicit public API
  class per module.
- **Layered** stays as shipped from phase 1.
- The vertical slice (§15) exists in all three: same entity, same endpoint, same frontend page,
  different structure.
- **An enforcement test in the generated project** per architecture — ArchUnit or equivalent:
  - hexagonal: the generated build fails if the domain imports Spring or the persistence adapter,
  - modular monolith: the build fails if one module reaches past another's public API class,
  - layered: the build fails if a repository imports a controller.
- Reference project per architecture per backend language, with the equality test extended to
  each.
- Matrix cells covering architecture × backend × build tool.

## Out of scope

Clean, event-driven and CQRS remain deferred (§11).

## Implementation notes

- Architecture is an *option* on the backend recipe, selecting file sets via `when` (§4) — not a
  separate recipe per architecture, and certainly not a separate backend recipe per architecture.
- The number of reference projects grows multiplicatively here. Consider whether a reference
  project per (language × architecture) with a single build tool, plus matrix coverage of the
  build-tool axis, is sufficient — and document whichever choice is made, since `kitbash-19`
  depends on it.
- The enforcement tests are shipped to users, so they must be fast and their failure messages
  must teach: "domain must not depend on Spring" beats a stack trace.

## Files and modules touched

`/recipes/backend-spring-java/arch/**`, `/recipes/backend-spring-kotlin/arch/**`,
`/reference/**`, `/verification/cells/**`.

## Done when

- The vertical slice exists in all three architectures and every combination builds green.
- Each generated project passes its own architecture test, and a deliberate violation makes the
  **generated** build fail.
- No two architectures differ only by folder names.
