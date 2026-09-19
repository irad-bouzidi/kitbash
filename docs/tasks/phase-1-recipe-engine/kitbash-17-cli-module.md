# kitbash-17-cli-module

**Phase** 1 — Recipe engine · **Depends on** `kitbash-12-pipeline-and-deterministic-package` · **Plan** §6, §12, §14

## Goal

Offline generation with no server, no database and no Spring context — the entry point the
verification matrix runs through.

## Context

§12 specifies that each verification cell *calls the generator through `cli` (no server)*. That
is not a convenience: it keeps verification independent of the API, its auth and its
persistence, so a matrix failure means the generator is broken rather than the deployment.

It also validates the module boundaries from §6 — if `cli` can generate a project while
depending only on `core`, `catalog` and `render`, the domain really is free of Spring.

## Scope

- `cli` module depending on `core`, `catalog` and `render` only. A Spring dependency appearing
  here is a build failure.
- Commands:
  - `kitbash generate --selection selection.json --out ./target [--zip]`
  - `kitbash validate --selection selection.json`
  - `kitbash catalog --json` — dumps the same metadata document the API serves, so the CLI and
    the API cannot drift.
- Exit codes: `0` success, non-zero on failure with the **structured §14 error envelope printed
  as JSON** to stderr, so CI can parse it.
- `--catalog <path>` to point at a recipe tree other than the bundled one, which is what makes
  local recipe development pleasant.
- Output to a directory by default and to a zip with `--zip`; both deterministic.

## Out of scope

No interactive prompting (that is `kitbash-44`), no publishing of a binary (that is
`kitbash-42`), no network calls of any kind.

## Implementation notes

- Keep argument parsing thin. The CLI is a shell around the same pipeline functions the API
  calls; any logic that appears here rather than in `core` will eventually differ between the
  two surfaces.
- `kitbash catalog --json` should be asserted equal to the API's `/metadata` payload in a test,
  which is the cheapest possible guard against drift.

## Files and modules touched

`/server/cli/**`, `/server/cli/src/test/**`.

## Done when

- The matrix runner generates via `cli` and never boots Spring.
- A failing generation prints a parseable §14 envelope and exits non-zero.
- `kitbash catalog --json` matches `/api/v1/metadata` byte for byte in a test.
