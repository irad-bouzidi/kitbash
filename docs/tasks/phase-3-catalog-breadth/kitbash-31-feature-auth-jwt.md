# kitbash-31-feature-auth-jwt

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-30-architectures-hexagonal-modular` · **Plan** §4, §11, §14

## Goal

The first genuinely cross-cutting feature recipe — the one that proves patches earn their keep.

## Context

§4 uses auth as *the* example of why generator-per-technology classes fail: auth touches the
backend **and** the router **and** compose **and** the CI file, so with one generator class per
technology it becomes coordination between classes, which is `if/else` wearing an interface.
With recipes, it is one directory plus a handful of typed patches against files other recipes
own.

§11 also explains what is deliberately *not* here: Keycloak and OIDC add a container, a
healthcheck, a startup race and a whole verification dimension, while JWT resource-server auth
proves the auth recipe shape at a fraction of the cost.

## Scope

- **Recipe** `/recipes/feature-auth-jwt` — `requires: [http-server]`, `provides: [auth]`.
- Backend: Spring Security resource server configuration, a protected endpoint, an unauthenticated
  health path, and integration tests asserting both 401 and 200.
- Frontend: token attachment in the API client and a login-state guard on the generated page.
- **Patches, across four files owned by four different recipes:**
  - `mergeYaml` into `application.yml` for the issuer and audience,
  - `addEnvVar` into `.env.example` **and** `compose.yaml` in one op,
  - `addDependency` per build tool,
  - `insertAtMarker` in the frontend router and the backend security config.
- **Error path** — a selection with auth and no backend fails `/validate` with the §14
  `PATCH_TARGET_MISSING` envelope and its hint, exactly as written in the plan. This is a test,
  not a manual check.
- Reference projects updated for the auth-on variant; matrix cells for auth on and off.
- The recipe's README notes that Keycloak/OIDC is deliberately deferred and why.

## Out of scope

Keycloak, session-based auth, refresh-token flows, user management (§11).

## Implementation notes

- The markers this recipe targets must already be placed and documented by the owning recipes
  (`kitbash-13`, `kitbash-14`). If a marker is missing, add it to the owning recipe in this MR
  and document it there — never insert by pattern-matching on surrounding code.
- Auth interacts with every architecture; make sure the hexagonal variant does not smuggle a
  security annotation into the domain, or `kitbash-30`'s enforcement test will catch it — which
  is exactly what it is for.

## Files and modules touched

`/recipes/feature-auth-jwt/**`, marker additions in backend and frontend recipes,
`/reference/**`, `/verification/cells/**`.

## Done when

- Auth on/off is one option; generated projects' tests pass in both states.
- Selecting auth without a backend produces the documented §14 error message verbatim.
- The hexagonal domain remains annotation-free with auth enabled.
