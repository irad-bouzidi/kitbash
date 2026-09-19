# kitbash-44-create-stack-npx

**Phase** 5 — Extension · **Depends on** `kitbash-15-metadata-and-validate-endpoints` · **Plan** §8, §17

## Goal

`npx create-stack` against the same API, for developers who would rather stay in a terminal than
open a web app.

## Context

§17 lists this in phase 5. It is cheap precisely because of the decision made in §8: the metadata
endpoint carries everything needed to render an interface, so a second client is a rendering
problem, not a product rewrite. Any option knowledge that turns out to be missing from `/metadata`
here is a genuine gap in the endpoint, and should be fixed there rather than patched in the CLI.

Note this is a *different* tool from `kitbash-42`'s binary: that one embeds a catalog and works
offline, this one talks to the server and is interactive.

## Scope

- A Node CLI published as `create-stack`, runnable via `npx`.
- Fetches `/api/v1/metadata` and **prompts interactively from it** — again, zero hardcoded option
  knowledge. Adding a recipe must change the prompts with no release of this tool.
- Calls `/api/v1/validate` as the user answers, so conflicts surface during the prompts rather
  than at the end.
- `--preset <id>` and `--selection <file>` for non-interactive and scripted use.
- Generates, downloads and unpacks in place, then prints the same start command the README states.
- Honours the §14 structured error envelope and exits non-zero with it on failure.
- Authenticates against the same OIDC provider, with a device-code or browser flow suitable for a
  terminal.

## Out of scope

Offline operation and an embedded catalog — that is `kitbash-42`'s binary, deliberately a separate
tool with different trade-offs.

## Implementation notes

- Prompt ordering should follow the metadata document's option group order, so the CLI and the web
  wizard present the same mental model.
- Cache the metadata response by ETag to keep startup fast, and show the catalog digest in
  `--version` output for the same reason the web footer does.

## Files and modules touched

New `/clients/create-stack/**` (or a top-level package), CI publish job, docs.

## Done when

`npx create-stack` produces the same project the web wizard does for the same selection, byte for
byte — asserted by a test that runs both paths and compares.
