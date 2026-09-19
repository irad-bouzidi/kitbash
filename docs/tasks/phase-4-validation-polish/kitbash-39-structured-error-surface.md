# kitbash-39-structured-error-surface

**Phase** 4 — Validation and polish · **Depends on** `kitbash-6-core-domain-model`, `kitbash-16-metadata-driven-wizard` · **Plan** §14

## Goal

Every failure names the stage, the recipe and the file, and every error carries a hint naming the
next action — end to end, from `core` through the API to the UI and the CLI.

## Context

§14 gives the envelope and the rule: *errors are typed enums in `core`, not strings assembled at
the controller. Every one carries a hint that names the next action.* The types were defined in
`kitbash-6`; this task is the audit that makes the promise true everywhere, after three phases of
code has accumulated throw sites.

The example in the plan is the standard to match — it names the error, the stage, the recipe, the
file, a plain message, a hint that tells the user what to select, and the selection hash that
makes the report reproducible.

## Scope

- **Audit every throw path** across `core`, `catalog`, `render`, `api`, `verify` and `cli`; replace
  ad-hoc exceptions and string messages with the typed `GenerationError` variants.
- Every variant carries a **non-empty hint naming the next action**, enforced at construction.
- The §14 envelope — `error`, `stage`, `recipe`, `file`, `message`, `hint`, `selectionHash` —
  returned by every endpoint and printed by the CLI as JSON.
- **The UI renders the envelope verbatim** (§14): no re-wording, no swallowed detail, no generic
  "something went wrong" fallback except for genuinely unmapped failures — and those are logged as
  a defect.
- Inline rendering in the wizard on the offending field where the error names an option (§9).
- `docs/errors.md` — the full code list, each with an example envelope, a cause and the fix.
- Validation failure reasons emitted by type as a metric (§14), feeding `kitbash-41`.

## Out of scope

No localization. No user-facing stack traces, ever.

## Implementation notes

- A test that enumerates every `GenerationError` variant and asserts a complete envelope is the
  only way this stays true as variants are added; make it fail on an unhandled variant by using an
  exhaustive switch rather than a registry lookup.
- The UI needs a rendering for each variant, but most will share one component — the point is that
  no variant falls through to a generic message unnoticed.
- Correlation id belongs in the envelope's transport headers or log line, not in the envelope body
  the plan specifies; keep the body exactly as §14 defines it.

## Files and modules touched

All server modules (throw sites), `/web/src/**` (error rendering), `/docs/errors.md`.

## Done when

- A test enumerates every error variant and asserts a complete envelope with a non-empty hint.
- The UI has a rendering for each, verified by a component test per variant.
- No user-facing error in the system is a bare string.
