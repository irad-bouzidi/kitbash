# kitbash-48-contributed-recipe-generation

**Phase** 5 — Extension · **Depends on** `kitbash-47-user-contributed-recipes` · **Plan** §4, §6, §10, §13

## Goal

Make an approved contributed recipe generable, which `kitbash-47` deliberately stopped short of.

## Context

`kitbash-47` built the controls, the sandbox and the review workflow, and it left one thing
unwired **on purpose**: the application still composes its `Catalog` from git alone, so a recipe
can be submitted, verified, approved and revoked but nothing generates from one.

The reason is in the threat model's §8. Composing the catalog before the render stage knows how to
route by provenance would put contributed templates in front of the **in-process** Pebble engine —
the one outcome worse than the feature not working, since every control in §6.4 assumes they never
reach it. So the composition is tested and unused rather than half-connected.

That is a safe place to stop and a bad place to stay: `DualSourceCatalog` currently has no caller,
and code with no caller rots.

## Scope

- **Route by provenance in the render stage.** A `FileEntry` carries its `owner`, and
  `RecipeId.contributed()` answers the question, so the split is available — what is missing is a
  stage that renders the two halves separately and merges the workspaces. Contributed entries go
  through `SandboxedRenderer`; shipped ones keep rendering in process, because they are not the
  untrusted input and a subprocess per generation would cost every user a JVM start.
- **Path templating for contributed entries too.** Paths are templated by the same engine as
  bodies, so a contributed recipe's paths have to be rendered in the sandbox and then put through
  `SafePaths` on the way back. The path rules are the second layer and they are the one that
  defends against a recipe rather than a user.
- **A holder the catalog is read through.** `Catalog` and `GenerationPipeline` are boot-time
  singletons at roughly fifteen injection sites, and approval has to take effect without a
  restart. The holder rebuilds on approve and on revoke.
- **Content from the database, not a directory.** `LoadedRecipeContent` is built from
  `LoadedRecipe`, which holds a filesystem path. A contributed recipe's files are a jsonb map, so
  `RecipeContent` needs a second implementation and the two need composing.
- Disclosure: the generated README and the lock say which contributed recipes were used, so
  somebody reading a project months later can see it without asking.

## Out of scope

Anything that widens what a contributed recipe may do. §6.1, §6.2 and §6.3 are not relaxed here,
and a change that makes contributed recipes equal to shipped ones is a change that removes a
control — see ADR 0004's consequences.

## Implementation notes

- Merging two workspaces is where a path collision between a shipped and a contributed recipe
  would surface. It must fail loudly with the §14 envelope naming both recipes, not let the later
  one win: silently overwriting a shipped file with a contributed one is a supply-chain attack
  with a friendly name.
- The sandbox renders one recipe per process. A selection with three contributed recipes is three
  processes, and the §13 wall clock is per pipeline rather than per process — so the budget has to
  be divided rather than multiplied.
- Resist making the holder refreshable from anywhere. Two call sites — approve and revoke — and a
  test that asserts there are only two.

## Files and modules touched

`/server/render/**` (the routing stage), `/server/core/**` (`RecipeContent` composition),
`/server/api/**` (the holder, and its refresh on approve/revoke),
`/docs/threat-model-contributed-recipes.md` §8.

## Done when

A contributed recipe can be submitted, approved and **generated from** without restarting the
server, its templates demonstrably rendered in the sandbox rather than in process, and revoking it
removes it from the catalog for the next generation — with a test that fails if a contributed
template is ever rendered by the in-process engine.
