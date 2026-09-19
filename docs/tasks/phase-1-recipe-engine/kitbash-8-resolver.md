# kitbash-8-resolver

**Phase** 1 — Recipe engine · **Depends on** `kitbash-7-recipe-manifest-and-loader` · **Plan** §4, §6, §17

## Goal

The pure function at the centre of the product: a selection goes in, an ordered recipe list
plus diagnostics come out. No I/O, no Spring, no clock, no randomness.

## Context

§17 singles this out: *build the resolver test suite in phase 1 and keep it fast and pure. It
is the component with real logic and the one where regressions stay invisible until a user's
project fails to compile.* The test suite is as much the deliverable as the code.

The design commitment being cashed in here is §4's "capabilities are the glue": the React
recipe declares `requires: [rest-api]`, and the resolver validates the selected set
structurally. There is no hand-written compatibility matrix, because that matrix is what the
Implementation Plan's approach would have degenerated into.

## Scope

- **Implied recipe expansion** — when a `requires` capability has exactly one provider in the
  catalog, select it automatically; when it has several, return a choice as a diagnostic
  rather than guessing.
- **Structural validation** — every `requires` satisfied by some `provides` in the selected
  set; no `conflictsWith` pair both selected; option values within their declared enum.
- **Topological sort** — base → backend → frontend → features → infra → CI, with ties broken
  by recipe id so the order is total and stable (§4). Determinism of the output zip depends on
  this being deterministic.
- **Cycle detection** with an error naming the members of the cycle, not just "cycle detected".
- **Output** — the effective option set (defaults applied), the resolved and ordered recipe
  list, conflicts, and warnings. This payload is exactly what `/validate` later returns, so
  design it as an API response even though it is an internal type today.
- **Test suite** — parameterized JUnit 5 over catalog combinations; pure; no Spring context;
  the whole resolver suite under five seconds so it can run on every save.

## Out of scope

No file planning, no rendering, no hooks. The resolver never touches the filesystem and never
reads a template.

## Implementation notes

- Keep the function signature honest: `(Catalog, Selection) -> Resolution`. If a clock, a
  logger or a config object creeps into the parameter list, the purity that makes the test
  suite fast has already been lost.
- Diagnostics should name the **option** the user can change, not the recipe the engine
  rejected. "Auth requires a backend; select one" beats "capability http-server unsatisfied".
- Table-driven tests: one table of valid combinations that must all resolve, one table of
  invalid pairings that must each produce exactly one diagnostic. Adding a recipe later means
  adding table rows, not writing new test classes.

## Files and modules touched

`/server/core/**` (resolver + resolution types), `/server/core/src/test/**`.

## Done when

- Every enumerated v1 combination resolves cleanly.
- Every invalid pairing produces exactly one diagnostic naming the offending option, asserted
  by the table-driven test.
- The resolver suite runs in under five seconds and boots nothing.
