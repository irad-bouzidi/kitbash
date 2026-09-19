# kitbash-14-recipe-frontend-react-vite

**Phase** 1 — Recipe engine · **Depends on** `kitbash-13-recipes-extract-phase0-stack` · **Plan** §4, §11, §15, §18

## Goal

A second axis in the catalog, so composition is exercised for real rather than in theory. One
backend and one frontend is the smallest configuration that proves the engine composes.

## Context

Until a second kind of recipe exists, every design choice in the engine is unfalsified: a
single backend recipe would work just as well with a checked-in tree. The frontend recipe is
what forces the compose file, the README, the `.gitignore` and the env file to genuinely come
from more than one source.

§18 settles that a standalone frontend is supported: `frontend-react-vite` without a backend
simply does not select the typed-client capability. It costs one conditional.

## Scope

- **Reference project** `/reference/react-vite-ts/` — real, compiling, tested: React 19 +
  TypeScript + Vite + pnpm, one page that lists and creates the backend's example entity, one
  Vitest component test, ESLint + Prettier, its own `.gitignore` and `Dockerfile`.
- **Recipe** `/recipes/frontend-react-vite` — `provides: [spa]`, `requires: [rest-api]`, and
  `requires: [openapi-spec]` only when the typed-client option is on (§4). The typed-client
  *wiring* is phase 3 (`kitbash-33`); the capability plumbing is here.
- **Standalone mode** — when no backend is selected, the recipe resolves, generates and builds,
  with the API base URL pointing at a configurable origin.
- **Patches, not copies:**
  - `addComposeService` for `web`, with `depends_on` the backend when one exists,
  - `appendLines` into `.gitignore` for Node,
  - `addEnvVar` for the API base URL, into `.env.example` and compose,
  - `mergeJson`/`addScript` for the frontend's npm scripts,
  - a README fragment describing only the frontend half.
- Matrix cells added for backend-only, frontend-only and full-stack.

## Out of scope

Next.js, Angular, Vue, SvelteKit and mobile remain deferred (§11). The OpenAPI typed client
lands in `kitbash-33`.

## Implementation notes

- The frontend page must call the backend's real endpoint from the vertical slice, so
  full-stack generation is verified end to end rather than as two unrelated folders in a zip.
- Keep the hand-written API call isolated in one file — `kitbash-33` replaces exactly that file
  with the generated client.
- Frontend-only generation is the case most likely to break silently; give it its own matrix
  cell now, not later.

## Files and modules touched

`/reference/react-vite-ts/**`, `/recipes/frontend-react-vite/**`, `/verification/cells/**`.

## Done when

Four combinations generate and build green: backend only, frontend only, both, and both with
Docker declined.
