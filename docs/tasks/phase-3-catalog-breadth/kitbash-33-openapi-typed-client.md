# kitbash-33-openapi-typed-client

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-32-feature-observability` · **Plan** §4, §15, §18

## Goal

The feature that makes this more than two scaffolds in one zip: a generated TypeScript client
that binds the frontend's types to the backend's DTOs.

## Context

§15 states it flatly — *without this, a full-stack generator is two unrelated folders in a zip.*
The mechanism is springdoc exposing an OpenAPI document, a build task running openapi-generator
into `frontend/src/lib/api`, and the generated page consuming that client. Change a backend DTO,
regenerate, and TypeScript fails at the call site.

The capability model is what gates it (§4, §18): the frontend recipe declares
`requires: [openapi-spec]` only when the typed-client option is on, so a standalone frontend
simply does not select that capability and costs one conditional.

## Scope

- Backend exposes its OpenAPI document via springdoc (already provided as the `openapi-spec`
  capability since `kitbash-13`).
- A **build task in both build tools** — Gradle and Maven — running openapi-generator to emit a
  TypeScript client into `frontend/src/lib/api`.
- The generated frontend page consumes that client, replacing the hand-written call isolated in
  `kitbash-14`.
- Wiring active **only when both a backend and a frontend are selected**; standalone frontend and
  standalone backend both still generate and build.
- `pnpm typecheck` wired into the generated project's CI so the binding is enforced, not merely
  present.
- The generation step must be reproducible; if its output is not deterministic, exclude it from
  the reference equality test with the exclusion documented in `docs/reference-projects.md`
  (§4, `kitbash-19`).
- Matrix cells: full-stack with typed client, full-stack without, frontend-only, backend-only.

## Out of scope

GraphQL and gRPC contracts remain deferred (§11). No client generation for mobile yet — that
arrives with `kitbash-45`.

## Implementation notes

- Decide explicitly whether the generated client is committed into the emitted project or
  produced at build time, and make the README say which. Both are defensible; silence is not.
- The openapi-generator version is a dependency like any other and belongs in the recipe manifest
  where the weekly bump job (`kitbash-36`) can see it.
- The DTO-change test is the phase exit criterion, so write it as an automated matrix cell:
  mutate a DTO field in a generated project, regenerate the client, assert `pnpm typecheck` fails.

## Files and modules touched

`/recipes/backend-spring-*/**` (openapi task), `/recipes/frontend-react-vite/**` (client
consumption), `/reference/**`, `/verification/cells/**`.

## Done when

In a generated full-stack project, changing a backend DTO field and re-running client generation
makes `pnpm typecheck` fail at the call site — **the phase exit test**, running as a matrix cell.
