# kitbash-6-core-domain-model

**Phase** 1 — Recipe engine · **Depends on** `kitbash-1-repo-scaffold` · **Plan** §4, §5, §7, §14, §19

## Goal

The vocabulary of the whole system, expressed in `core` as records and sealed interfaces,
with no Spring and no Lombok. Every later task speaks these types.

## Context

§19 names the concepts that must stay independent: `Recipe`, `Capability`, `PatchOp`,
`Selection`, `FilePlan`. If those are modelled well, adding a technology means adding a
directory. If they are modelled badly, it means editing the generation workflow, which is
the failure mode that kills scaffolding projects.

Java 21 was chosen partly because records, sealed interfaces and pattern-matching `switch`
recover most of the modelling win the team would have got from Kotlin (§18). This task is
where that payoff is either taken or squandered — in particular, an exhaustive switch over a
sealed `PatchOp` with no `default` branch is what makes adding an operation a compile error
in every place that must handle it.

## Scope

**Data carriers, all records:**

- `SelectionEnvelope` — `schemaVersion`, `projectName`, `options`, `variables` (§7).
- `Selection` — the parsed, typed form.
- `RecipeId`, `RecipeVersion` (semver, comparable), `Recipe`, `Capability`, `OptionSpec`
  (id, type, values, default, help), `RecipeKind` (base | backend | frontend | mobile |
  feature | infra | ci).
- `FileEntry` (path, content supplier, mode), `FilePlan` (ordered entries + pending patches).
- `Lock` — recipe id → exact version, plus the catalog digest (§7).

**Sealed `PatchOp`,** one record per operation in the §4 table:
`AddDependency`, `MergeYaml`, `MergeJson`, `AddScript`, `InsertAtMarker`, `AppendLines`,
`AddEnvVar`, `AddComposeService`. Every op carries the id of the recipe that owns it and the
path it targets — §4 requires that a patch names its owner so failures can name it too.

**Sealed `GenerationError`,** typed codes matching §14 — `UNKNOWN_RECIPE`,
`CAPABILITY_UNSATISFIED`, `CONFLICT`, `CYCLE`, `PATCH_TARGET_MISSING`, `PATCH_COLLISION`,
`INVALID_IDENTIFIER`, `PATH_ESCAPE`, `LIMIT_EXCEEDED`, `RENDER_FAILED` — each carrying
`stage`, `recipe`, `file`, `message`, `hint`, `selectionHash`.

**Canonicalization** — sorted keys, defaults elided, producing the `selectionHash` used for
caching, dedupe and verification keys (§7, §10, §12).

**Schema migration** — `SelectionMigrations`, mapping envelope version *n* → *n+1*, so the
selection JSON can evolve without breaking stored presets and generations.

## Out of scope

No resolution logic, no rendering, no patch application — only the types those operate on.
No Spring annotations anywhere in this module, ever.

## Implementation notes

- Errors are typed enums in `core`, **not strings assembled at the controller** (§14). Each
  variant carries a hint naming the next action; make the hint a required constructor
  parameter so it cannot be forgotten.
- Canonicalization must be stable across JVM versions and locales — sort with an explicit
  comparator, serialize with an explicit charset, never rely on `HashMap` iteration order.
- Write the migration framework now even with a single schema version. Adding it after the
  first stored preset exists is a data-migration problem instead of a code problem.

## Files and modules touched

`/server/core/src/main/java/**`, `/server/core/src/test/java/**`.

## Done when

- A pattern-matching `switch` over `PatchOp` compiles exhaustively with no `default` branch,
  and adding a ninth op breaks compilation in every applier — demonstrated in the MR.
- Canonicalization has round-trip and stability tests, including one asserting that two
  semantically identical selections with different key orders hash identically.
- `core` still declares stdlib-only dependencies; the guard from `kitbash-1` passes.
