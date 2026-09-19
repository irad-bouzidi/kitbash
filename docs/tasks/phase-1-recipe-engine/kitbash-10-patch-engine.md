# kitbash-10-patch-engine

**Phase** 1 — Recipe engine · **Depends on** `kitbash-6-core-domain-model` · **Plan** §4, §14

## Goal

Make composition real. Several recipes modify the same file — `build.gradle.kts`,
`application.yml`, `compose.yaml`, `package.json`, `.gitignore` — without producing broken
syntax.

## Context

§4 is blunt about the alternative: *free-text appending produces broken syntax within a week.*
Patches are typed and format-aware, every applier parses and re-serializes rather than
string-appending, and every patch is idempotent and names the recipe that owns its target.

This is the component that makes cross-cutting features possible at all. Auth touches the
backend, the router, compose and the CI file; without typed patches that becomes coordination
between generator classes, which is `if/else` wearing an interface.

## Scope

One applier per `PatchOp`, dispatched by an exhaustive pattern-matching switch:

| Op | Targets | Behaviour |
| --- | --- | --- |
| `addDependency` | `build.gradle.kts`, `pom.xml`, `package.json` | Insert into the right block, dedupe, respect the version catalog |
| `mergeYaml` | `application.yml`, `compose.yaml`, CI files | Deep merge; **fails on scalar collision** rather than picking a winner |
| `mergeJson` | `package.json`, `tsconfig.json` | Deep merge, arrays union |
| `addScript` | `package.json` | Add an npm script; fail loudly on name collision |
| `insertAtMarker` | any text file | Insert at a `// kitbash:imports` marker placed by the owning recipe |
| `appendLines` | `.gitignore`, `.env.example` | Idempotent line append |
| `addEnvVar` | `.env.example` **and** `compose.yaml` | Adds the variable in both places in one op |
| `addComposeService` | `compose.yaml` | Service + healthcheck + `depends_on` wiring |

Plus:

- **Idempotency** — applying any op twice equals applying it once, asserted per op.
- **Ownership** — a patch whose target was not produced by any selected recipe fails at
  validate time with `PATCH_TARGET_MISSING` and the §14 hint, never silently (§4).
- **Marker discipline** — `insertAtMarker` against a missing marker is an error naming the
  recipe that was supposed to place it.
- Application order is recipe order from the resolver, so the result is deterministic.

## Out of scope

No new op types beyond the eight in §4. If a recipe seems to need a ninth, that is a design
discussion, not a quiet addition.

## Implementation notes

- Use real parsers: a YAML parser for YAML, a JSON parser for JSON, and for `build.gradle.kts`
  a structured block-aware editor rather than a regex. `pom.xml` gets a real XML tree.
- Preserve formatting where the parser allows, because a diff-hostile generator is a generator
  nobody reads the output of.
- `mergeYaml` failing on scalar collision is a feature: two recipes silently overwriting the
  same `application.yml` key is precisely the bug class this design exists to prevent.
- Write the golden test early — it is the fastest way to see whether an applier's output is
  something a human would have written.

## Files and modules touched

`/server/core/**` (appliers), `/server/core/src/test/**`, fixtures under `src/test/resources`.

## Done when

- Every op has an idempotency test and a collision test.
- A golden test applies all eight ops to one project and the result parses under each format's
  real parser (Gradle, Maven, YAML, JSON).
- A patch against an unproduced target yields the exact §14 error envelope, hint included.
