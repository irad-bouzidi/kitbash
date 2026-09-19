# kitbash-9-render-pebble-sandbox

**Phase** 1 — Recipe engine · **Depends on** `kitbash-6-core-domain-model` · **Plan** §4, §5, §6, §13

## Goal

The `render` module turns a plan into text, safely and deterministically. Paths are templated
as well as bodies.

## Context

Pebble was chosen over Thymeleaf because the output here is source code, not HTML, and over
logic-less engines because those fight you the moment a template needs a conditional (§2, §5).
That power is exactly why it has to be sandboxed: a generator writes strings into files someone
will then execute (§13).

## Scope

- Pebble configured with unsafe extensions removed: **no reflection, no arbitrary method
  invocation, no `include` from user-controlled paths** (§13).
- The variable map holds **validated primitives only** — never live service objects, never the
  catalog, never a Spring bean.
- **Path templating** — `files/src/main/java/{{ packagePath }}/Application.java.peb` renders
  to a real path; the `.peb` suffix is stripped; files without it are copied through untouched;
  binary files are never run through the engine.
- A small, documented helper/filter set that recipes actually need: `packagePath`, `camel`,
  `kebab`, `pascal`, `snake`. Every helper added must be documented in `docs/recipe-format.md`
  in the same MR.
- Rendering parallelized across files on virtual threads, with results collected in a
  deterministic order.
- Render failures produce `RENDER_FAILED` carrying the recipe, the template path and the line,
  not a raw Pebble stack trace.

## Out of scope

No patching, no zip writing, no plan construction. Path *safety* checks (traversal, reserved
names) are specified here as a requirement but implemented in full by `kitbash-20` — this task
must at minimum not make them impossible.

## Implementation notes

- Build the Pebble engine once, immutably, at startup; a per-request engine is both slow and a
  place for state to leak between renders.
- Disable the template cache keyed on mutable state, or key it on recipe content hash —
  a stale cached template is a determinism bug that only appears in production.
- Test the sandbox adversarially: a template that tries `{{ ''.getClass() }}`, one that tries
  to include `/etc/passwd`, one that references an undefined variable. All three must fail with
  typed errors.

## Files and modules touched

`/server/render/**`, `/docs/recipe-format.md`.

## Done when

- Adversarial templates fail with typed errors rather than rendering or throwing raw
  exceptions, covered by tests.
- Identical inputs render byte-identical outputs, including under parallel rendering.
- Every available filter is documented.
