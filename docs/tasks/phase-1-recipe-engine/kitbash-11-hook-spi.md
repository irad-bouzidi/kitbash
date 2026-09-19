# kitbash-11-hook-spi

**Phase** 1 — Recipe engine · **Depends on** `kitbash-8-resolver`, `kitbash-10-patch-engine` · **Plan** §4

## Goal

The narrow, typed escape hatch for the handful of things that are genuinely awkward as data —
and the guard rails that stop it becoming the place all the logic ends up.

## Context

§4 names the cases: computing a Gradle version catalog from the union of selected recipes,
emitting a lockfile, deriving a compose service graph. Rather than bending the manifest format
into a programming language, a recipe may declare one hook. Hooks run in the plan stage and
return patch ops like everything else.

The stated rule matters as much as the interface: *if more than ~3 recipes need a hook, the
manifest format is missing a feature and should gain one instead.*

## Scope

- The SPI, exactly as §4 specifies:

  ```java
  public interface RecipeHook {
      String recipeId();
      List<PatchOp> contribute(PlanContext ctx);   // pure; no I/O, no shell
  }
  ```

- Hooks are **registered in `core`**, not loaded from the recipe directory. A recipe directory
  can declare that it *has* a hook; it cannot supply code.
- `PlanContext` exposes resolved recipes, the effective option set and validated variables —
  read-only, with no filesystem, no network, no clock.
- **First hook:** the Gradle version catalog, computed from the union of selected recipes, so
  each recipe declares the dependencies it needs and the catalog assembles itself.
- Hook output is ordinary patch ops, subject to the same idempotency and ownership rules.
- A contract test every hook must pass: pure, deterministic, no I/O, returns ops owned by its
  own recipe id.
- `docs/hooks.md` — the interface, the registered list, and the "more than three means fix the
  manifest" rule stated as policy.

## Out of scope

No user-supplied hooks, no scripting, no classloading from `/recipes`. That is `kitbash-47`
territory and carries a whole threat model.

## Implementation notes

- Enforce purity structurally where possible: give `PlanContext` no capability to do I/O, so a
  hook would have to reach for a static to misbehave, and let the contract test catch that.
- Registering hooks by recipe id in a map in `core` is enough; a service-loader mechanism
  invites exactly the dynamic loading this design excludes.
- Keep the version catalog hook's output reviewable — a generated `libs.versions.toml` that a
  human cannot read defeats the point of emitting one.

## Files and modules touched

`/server/core/**` (SPI, registry, version catalog hook), `/docs/hooks.md`.

## Done when

- The version catalog hook replaces hand-written catalog entries in the JVM recipes.
- A hook attempting I/O fails the contract test.
- `docs/hooks.md` lists every registered hook, and the list has at most three entries.
