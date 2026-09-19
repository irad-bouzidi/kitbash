# kitbash-43-recipe-authoring-sdk

**Phase** 5 — Extension · **Depends on** `kitbash-19-reference-equality-test` · **Plan** §4, §17

## Goal

Make writing a recipe a supported activity with tools and documentation, rather than tribal
knowledge held by whoever wrote the engine.

## Context

§17 lists the authoring SDK — manifest schema plus a local test harness — as the first item of
phase 5. It is the prerequisite for both of the interesting things that follow: contributions from
other teams, and eventually user-contributed recipes (`kitbash-47`), which cannot be reviewed
sensibly if nobody outside the core team can author one.

The workflow it has to support is the one §4 established: edit a real reference project, run the
equality test, port the diff.

## Scope

- **Published manifest JSON Schema** (from `kitbash-7`) with editor integration — schema store
  registration or a documented `$schema` line so authors get completion and validation while
  typing.
- **Local test harness:**
  - render one recipe against a fixture selection,
  - diff the result against an expected tree,
  - run the patch idempotency and ownership checks for that recipe alone,
  - run the reference equality check for that recipe alone.
  All of it without booting the server and in a few seconds.
- **`kitbash recipe new`** — scaffolds a recipe directory from an existing reference project,
  including the manifest skeleton and the variable extraction starting point.
- **`docs/authoring-recipes.md`** — the complete workflow: choose capabilities, derive from a
  reference project, express cross-cutting changes as patches, place markers, add matrix cells,
  and the hook rule from §4 (more than ~3 hooks means the manifest is missing a feature).
- A worked end-to-end example in the docs, following one small recipe from empty directory to
  green matrix cell.

## Out of scope

Recipe distribution, a registry, versioned recipe packages — all of that belongs to `kitbash-47`
or later, if ever.

## Implementation notes

- The harness must give the same verdicts CI gives. An SDK that passes locally and fails in CI
  teaches people to distrust the tools.
- Scaffolding should produce something that passes validation immediately, even if it generates
  nothing useful — an author's first success should be five minutes in, not an hour.

## Files and modules touched

`/server/cli/**` (harness commands), `/recipes/_schema/**`, `/docs/authoring-recipes.md`.

## Done when

A maintainer who has never touched the codebase adds a working recipe using only the docs and the
harness — demonstrated by having one actually do it, with their feedback folded back into the docs
in the same MR.
