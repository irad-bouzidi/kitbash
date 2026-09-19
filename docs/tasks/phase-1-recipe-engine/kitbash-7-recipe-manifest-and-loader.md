# kitbash-7-recipe-manifest-and-loader

**Phase** 1 — Recipe engine · **Depends on** `kitbash-6-core-domain-model` · **Plan** §4, §7, §10

## Goal

`recipe.yaml` becomes a specified, schema-validated format, and the `catalog` module loads
the entire `/recipes` tree at boot — or refuses to start with a message naming the file and
the field.

## Context

§10 is emphatic that the recipe catalog is **not in the database**: recipes live in the repo,
are validated at boot and held in memory, so they are reviewable, diffable and versioned with
the code that renders them. That makes the loader the only gate between a malformed recipe and
a broken user download, so it fails loudly and early rather than at render time.

The catalog digest computed here is load-bearing well beyond this task: it is part of the zip
cache key (§10), the verification dedupe key (§12), the generation lock (§7) and the footer of
the web UI (§9).

## Scope

- Manifest fields exactly as §4: `id`, `version` (recipe semver, independent of the framework
  version), `frameworkVersion`, `kind`, `label`, `provides`, `requires`, `conflictsWith`,
  `options[]`, `variables.required[]`, `files[].{from,when}`, `patches[]`.
- A JSON Schema for the manifest checked in at `/recipes/_schema/recipe.schema.json`, used
  both by the loader and by editors.
- Loader validation, each with a distinct error: duplicate recipe id; unknown `kind`; malformed
  semver; a `when` expression referencing an undeclared option; a capability in `requires` that
  no recipe `provides`; a `patches[].target` naming an unknown operation; a `files[].from` glob
  matching nothing.
- **Catalog digest** — sha256 over the sorted set of `(recipeId, version, contentHash)`, where
  `contentHash` covers the manifest and every file in the recipe directory (§7).
- In-memory catalog held immutably after boot; a dev-only reload endpoint or file watcher is
  acceptable but must recompute the digest.
- `docs/recipe-format.md` generated from or kept honest against the schema, with one fully
  worked example.

## Out of scope

No resolution (that is `kitbash-8`), no rendering of `files[]`, no database tables — a test
asserts no migration describes recipes, technologies or architectures (§10).

## Implementation notes

- Validate against the JSON Schema first, then apply the semantic checks the schema cannot
  express. Two layers keep the error messages specific.
- The digest must be stable across machines: sort paths with a fixed comparator, hash bytes not
  strings, and exclude nothing implicitly — if something is excluded, name it in the docs.
- Keep the `when` expression language tiny and documented from day one. It is the surface most
  likely to grow accidental features.

## Files and modules touched

`/server/catalog/**`, `/recipes/_schema/**`, `/docs/recipe-format.md`.

## Done when

- A deliberately broken manifest fails application startup with a message naming the file, the
  field and the fix, covered by a test per validation rule.
- The digest is identical across two machines for the same tree and changes when any recipe
  byte changes.
- No database object describes the catalog.
