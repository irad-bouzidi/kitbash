# kitbash-19-reference-equality-test

**Phase** 1 — Recipe engine · **Depends on** `kitbash-13-recipes-extract-phase0-stack`, `kitbash-14-recipe-frontend-react-vite` · **Plan** §4

## Goal

Bind every recipe to its reference project with a test, so drift between *the thing we
maintain* and *the thing we emit* becomes a failing build rather than a user's broken project.

## Context

§4 states this is enforced, not merely recommended: a CI test renders each recipe with the
reference variable set and asserts the output equals the checked-in reference project byte for
byte. Without it, the reference projects quietly become documentation that lies — someone fixes
a bug in the reference, nobody ports it into the recipe, and the emitted project keeps the bug.

It is also the mechanism that makes recipe maintenance pleasant: you edit a real project, run
the test, and the diff tells you exactly what to port.

## Scope

- A checked-in `reference-variables.json` per reference project (started in `kitbash-2`).
- A CI test per reference project: render the corresponding recipe set with those variables and
  compare against the reference tree **byte for byte**.
- An explicit, short, documented ignore list — anything excluded must be named and justified in
  `docs/reference-projects.md`, not silently skipped.
- Failure output is a real diff: which files are missing, which are extra, and for differing
  files the first differing line with context. "Trees are not equal" is not an acceptable
  failure message.
- `docs/reference-projects.md` — the maintenance workflow: edit the reference project, run the
  test, port the diff into the recipe, re-run.
- The test runs on every merge request, not only nightly; it is fast because nothing is compiled.

## Out of scope

No compilation here — that is what the verification matrix does. This test answers a different
question: *does the recipe still emit the project we maintain?*

## Implementation notes

- Compare normalized bytes, with the normalization rules identical to the ones the packager
  applies (line endings, file modes). Two different normalizations is how a test like this
  becomes flaky.
- Generated-at-build-time artifacts (a generated TS client, a lockfile) either get excluded and
  documented, or get generated into the reference project and committed. Pick one per artifact
  and record the choice.
- Run it per reference project rather than as one giant assertion, so a failure names the stack.

## Files and modules touched

`/server/verify/**` or `/server/catalog/src/test/**`, `/reference/*/reference-variables.json`,
`/docs/reference-projects.md`, CI config.

## Done when

Editing a reference project without updating its recipe fails CI, and the failure message names
the exact file and the first differing line — demonstrated in the MR discussion.
