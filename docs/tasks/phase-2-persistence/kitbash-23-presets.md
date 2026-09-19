# kitbash-23-presets

**Phase** 2 — Persistence · **Depends on** `kitbash-22-oidc-auth-and-rate-limits`, `kitbash-16-metadata-driven-wizard` · **Plan** §3, §7, §9, §10

## Goal

"Save as template", named correctly (§3) and versioned correctly (§7): a preset is a user-owned
saved selection that tracks the latest catalog by default.

## Context

§7 reconciles the two source plans here, and the reasoning is worth keeping in view while
implementing: the common case is *give me our standard service again*, and a preset frozen on
Spring Boot 3.2 is a trap. So presets track latest. Reproducibility is not lost, because it
lives on the **generation**, which carries a lock (`kitbash-24`). Pinning stays available per
preset for compliance cases.

§9 also folds the Implementation Plan's Dashboard into this page: a counter tile page earns
nothing, and the preset list is what a returning user actually wants, one click from a cold
load.

## Scope

- **API** — `GET POST /api/v1/presets`, `GET PUT DELETE /api/v1/presets/{id}`,
  `POST /api/v1/presets/{id}/generate`.
- **Visibility** — `private | team | public`; `public` gated by role (§13).
- **Version policy** — `track_latest` by default; `pinned` records `pinned_recipes` as
  recipe id → version.
- **Revisions** — `revision` bumped on update, with `(owner_id, name, revision)` unique, so an
  edit does not destroy the previous definition.
- **Stale preset handling** — a preset referencing a recipe that no longer exists, or a pinned
  version that has been removed, reports a clear actionable error at load time rather than
  failing during render.
- **Web** `presets/`:
  - the list page, which is the landing page for a returning user (there is no Dashboard, §9),
  - a save dialog invoked from the wizard's bottom bar,
  - a detail view showing the resolved stack and the version policy,
  - one-click generate.

## Out of scope

Sharing by link (`kitbash-25`), history and replay (`kitbash-24`), preset-level verification
badges (`kitbash-38`).

## Implementation notes

- Saving a preset stores the **selection**, not the resolution. Resolution happens at generate
  time, which is what "tracks latest" means mechanically.
- A pinned preset still records only what it pinned; anything unpinned resolves fresh. Mixed
  policies are normal and should be displayed clearly on the detail page.
- The save dialog should default the name from the project name but not couple them — a preset
  outlives any single project.

## Files and modules touched

`/server/api/**` (preset controller, service, repository), `/web/src/presets/**`,
wizard bottom bar wiring.

## Done when

Save a preset, reload cold, regenerate in one click, and the result matches what the wizard
produced — with a test covering the stale-preset error path as well.
