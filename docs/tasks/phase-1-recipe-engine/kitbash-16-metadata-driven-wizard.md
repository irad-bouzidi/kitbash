# kitbash-16-metadata-driven-wizard

**Phase** 1 — Recipe engine · **Depends on** `kitbash-15-metadata-and-validate-endpoints` · **Plan** §2, §9

## Goal

Replace the phase-0 form with the real product surface: a one-page wizard that renders itself
from the metadata document and contains zero knowledge of any specific technology.

## Context

§2 settles the shape — one page with grouped sections and a live right-rail summary, not a
ten-step wizard; steps add friction at this option count. §9 then specifies the interaction
details that matter more than they sound, and they are specified rather than left to taste
because each one has a failure mode that makes the catalog feel arbitrary or broken.

## Scope

Directory layout per §9:

```
web/src/
  catalog/      # fetch + typed access to metadata (TanStack Query)
  wizard/
    fields/     # one component per option type: enum, boolean, string, multi-select
    FieldRenderer.tsx   # option type -> component; the only switch in the app
    useSelection.ts     # Zustand store + URL sync
    useValidation.ts    # debounced POST /validate
```

- **`FieldRenderer`** maps option *type* to component. It is the only switch in the
  application, and it switches on type, never on option id.
- **Selection state** in Zustand; server catalog data in TanStack Query — cached server data
  and local ephemeral state kept apart deliberately (§5).
- **React Hook Form + Zod**, with the schema built **at runtime from catalog metadata** (§5).
  A static schema cannot follow a catalog that changes without a frontend deploy.
- **Validation debounced 250 ms**; conflicts render **inline on the offending field**, never as
  a top banner (§9).
- **Blocked options stay visible and disabled**, with the reason on hover. Hiding them makes
  the catalog feel arbitrary (§9).
- **URL sync on every change** — back and forward work, and a link captures a configuration.
- **Layout** — left column of option groups, right rail with the live resolved stack summary,
  persistent bottom bar with Preview / Save as preset / Generate. The first two are present but
  disabled until phase 2 lands them.
- **Generate downloads via anchor or form post**, not `fetch` + blob, so native download
  progress shows.
- Dark mode, keyboard navigation across groups, and the **catalog digest in the footer**.
- The phase-0 form is deleted.

## Out of scope

Preview pane, presets, history, verification badges — phases 2 and 4. The bottom-bar buttons
for those are rendered disabled, with the reason.

## Implementation notes

- The temptation to special-case one option ("just for the package name field") is the exact
  failure this design exists to prevent. If an option needs special behaviour, that behaviour
  belongs in its **type**, declared in the manifest.
- Right-rail summary should show the resolved stack — including recipes the resolver implied —
  not the raw selection, so users see what they are actually getting.
- URL sync needs a stable, short encoding; reuse the canonical selection form from `core`
  rather than inventing a second one.

## Files and modules touched

`/web/src/catalog/**`, `/web/src/wizard/**`, `/web/src/lib/**`, deletion of `Phase0Form.tsx`.

## Done when

Adding a recipe directory and restarting the server changes the wizard with **zero** frontend
commits — demonstrated in the MR with a throwaway recipe and a screenshot, then reverted.
