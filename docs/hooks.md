# Hooks

A hook is the narrow, typed escape hatch for the few things that are genuinely awkward to express
as data (§4). It is not a plugin system, and the rule below matters as much as the interface.

> **If more than about three recipes need a hook, the manifest format is missing a feature and
> should gain one instead.**

That is policy, not advice. A test asserts the registry holds at most three entries, so the
conversation happens when the limit is reached rather than two hooks later.

## The interface

```java
public interface RecipeHook {
    String recipeId();
    List<PatchOp> contribute(PlanContext context);   // pure; no I/O, no shell
}
```

A hook runs in the **plan stage** and returns ordinary patch operations, subject to the same
idempotency and ownership rules as any other op — so nothing downstream has to know a hook was
involved, and a hook cannot do anything a manifest could not have done more verbosely.

## Hooks are registered in `core`, never loaded from `/recipes`

A recipe directory declares that a hook exists for it:

```yaml
hook: true
```

It cannot supply the code. The implementation is registered in a map in `dev.kitbash.core.hook`,
reviewed with the rest of the source and shipped with the application. A recipe that declares
`hook: true` with nothing registered fails at plan time rather than silently producing a project
missing whatever the hook was supposed to compute.

There is no `ServiceLoader` and no classpath scanning. Dynamic discovery is the thing this design
excludes, and with a ceiling of three there will never be enough hooks for the indirection to pay
for itself. User-supplied recipe code is
[`kitbash-47`](tasks/phase-5-extension/kitbash-47-user-contributed-recipes.md) and carries a threat
model this does not (§13).

## What a hook can see

`PlanContext` carries the resolved recipes (in order), the effective option set, the validated
variables and the capabilities the stack provides. It offers **no filesystem, no network, no clock,
no randomness and no catalog**.

Purity is stated structurally rather than as a comment, because a comment holds until the first
person who needs a file path. A hook that wants to misbehave has to reach for a static — and the
contract test scans each hook's compiled class for exactly that: `Files`, `ProcessBuilder`,
`Runtime`, `Random`, `Instant`, `currentTimeMillis`, `getenv`. The check is deliberately crude and
defeatable by reflection; what it buys is that the *ordinary* mistake, somebody adding a timestamp
because it was convenient, cannot pass review unnoticed.

Every registered hook inherits the whole contract test suite: ops owned by its own recipe id,
deterministic output for a given context, no instance state, no forbidden references.

## Registered hooks

| Recipe | Hook | What it computes |
| --- | --- | --- |
| `build-gradle-kts` | `VersionCatalogHook` | `gradle/libs.versions.toml`, from the union of the selected recipes' `addDependency` declarations |

### `VersionCatalogHook`

This is the case §4 names first, and it is a fair illustration of when a hook is warranted. Each
recipe knows the dependencies it needs, but a version catalog is a property of the **set**: one
`[versions]` entry per alias however many recipes asked for it, one `[libraries]` line each, sorted.
Expressing that in a manifest would mean either duplicating the catalog in every recipe or inventing
a cross-recipe aggregation syntax — a programming language with extra steps.

So recipes declare ordinary patches:

```yaml
patches:
  - op: addDependency
    target: "."
    configuration: implementation
    coordinate: org.springframework.boot:spring-boot-starter-web
    versionRef: spring-boot-starter-web
```

and the catalog assembles itself. The owning recipe places two markers in its
`gradle/libs.versions.toml` template:

```toml
[versions]
# kitbash:versions

[libraries]
# kitbash:libraries
```

Three behaviours worth knowing:

- **A coordinate with a version** (`org.assertj:assertj-core:3.27.3`) produces both a `[versions]`
  entry and a `version.ref`.
- **A coordinate without one** produces a library line with no version reference. Spring Boot
  starters get their version from a BOM, and inventing one here would pin something upstream
  deliberately left floating, which then rots silently while the BOM moves.
- **A dependency declared without a `versionRef`** stays out of the catalog entirely. Not every
  dependency wants an alias, and forcing one would make the catalog a worse index of what matters.

The output is meant to be read: a generated `libs.versions.toml` a human cannot follow defeats the
point of emitting one, so entries are sorted by alias and duplicates across recipes collapse to one.

### A hook belongs to a recipe, which is what made Maven cheap

§28 added a second build tool and put this design under its first real test. A version catalog is a
*Gradle* artefact; Maven has no equivalent to assemble. The obvious failure would have been for
`VersionCatalogHook` to grow a branch — "if the selection is Maven, contribute nothing" — and for
every later build tool to add another.

It did not, because the hook is registered against `build-gradle-kts` rather than against the
pipeline. Selecting Maven selects a different recipe, that recipe declares no hook, and the catalog
question answers itself by never being asked. The recipes that declare the dependencies did not
change at all:

- a **versioned** coordinate (`org.flywaydb:flyway-core:11.10.5`) carries its version into whichever
  build file it lands in — a `[versions]` entry and a `version.ref` on Gradle, a `<version>` element
  on Maven;
- an **unversioned** one is left floating in both, because both stacks pin it from above: the BOM on
  Gradle, the `spring-boot-starter-parent` on Maven;
- `versionRef` is read by the catalog hook and ignored by the pom writer, which is the correct
  reading of an alias for a catalog that does not exist.

What Maven did need is the thing a hook could not have supplied anyway: `addDependency` had to stop
naming a build file and start naming a **module**, so that one declaration means `build.gradle.kts`
or `pom.xml` depending on what the selection put there. That is a property of the patch operation,
not of the recipe set, and `AddDependencyParityTest` is where it is held to account.

## Adding a hook

1. Be sure it is not a manifest feature in disguise. Re-read the ceiling above.
2. Implement `RecipeHook` in `dev.kitbash.core.hook`, with no instance state.
3. Register it in `RecipeHooks`. One recipe, one hook — two registrations for the same recipe fail
   at class-initialisation time.
4. Set `hook: true` in the owning recipe's manifest.
5. Add a row to the table above in the same change. An undocumented hook is a piece of behaviour
   nobody can account for when a generated project contains something unexpected.
