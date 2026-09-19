# kitbash-15-metadata-and-validate-endpoints

**Phase** 1 — Recipe engine · **Depends on** `kitbash-13-recipes-extract-phase0-stack` · **Plan** §8, §9, §19

## Goal

The hinge of the entire design: the server describes the catalog completely, and the client
knows nothing about what a Spring Boot or a React project is.

## Context

§8 puts it plainly — the metadata endpoint carries option groups, types, dependency rules,
labels, help text and per-combination verification badges, the wizard renders itself from that
document, and adding a recipe is therefore a backend-only change. *Resist every temptation to
hardcode an option name in React.*

`/validate` exists as a separate, cheap endpoint because it runs only pipeline stages 1–2 (§6),
which is what makes calling it on every option change affordable.

## Scope

- **`GET /api/v1/metadata`** — the whole catalog: recipes, kinds, option groups, option types,
  defaults, labels, help text, `requires`/`conflictsWith` rules, recipe versions, framework
  versions, and a verification-status field (populated later by `kitbash-38`).
  ETag-cached and immutable per catalog digest.
- **`POST /api/v1/validate`** — resolve only. Returns conflicts, warnings and the effective
  option set, in the shape the resolver already produces. Fast enough to call on every
  keystroke-debounced change.
- **`GET /api/v1/health`**, **`GET /api/v1/info`** — Actuator, with `info` exposing the catalog
  digest and recipe count (§8). That digest is what makes a bug report actionable.
- **OpenAPI spec published by the server**, and the web client generated from it (§9) so the
  frontend's types follow the API automatically.
- Error responses use the §14 envelope from the first commit.

## Out of scope

`/preview`, `/generate` history, presets, share links and auth — later tasks. No verification
badge data yet, only the field.

## Implementation notes

- Design the metadata document for a client that renders it generically: option groups carry
  display order and labels, option types are a closed set the client switches on, and every
  human-readable string comes from the server.
- ETag on the catalog digest gives correct caching for free and makes "which catalog was this?"
  answerable from a response header.
- Keep `/validate` free of side effects and free of persistence — it will be called constantly.

## Files and modules touched

`/server/api/**`, `/server/catalog/**` (metadata document assembly), OpenAPI config,
`/web/src/lib/` client generation wiring.

## Done when

- A client can render the entire wizard from `/metadata` alone.
- A contract test asserts no option id or label is hardcoded in `web/src` outside the generated
  client.
- `/info` reports the catalog digest, and it matches the one the loader computed.
