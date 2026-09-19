# kitbash-20-input-validation-and-caps

**Phase** 1 — Recipe engine · **Depends on** `kitbash-12-pipeline-and-deterministic-package` · **Plan** §13, §14

## Goal

Treat every input as hostile. A generator takes user strings and writes them into files someone
will then execute; this task is the set of defences that assumption demands.

## Context

§13 is the specification and it is deliberately strict about one thing that looks like a
usability decision but is a correctness decision: **reject with a message, never sanitize by
stripping.** Silent rewriting produces surprising output — a user asks for one package name and
gets another, and finds out at compile time.

Caps live at the plan stage because that is the last moment before cost is incurred (§6, §13).

## Scope

**Identifier allowlists:**

- `groupId` / package: `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$`
- artifact and project name: `^[a-z][a-z0-9-]{0,63}$`
- Java **and** Kotlin reserved words rejected in package segments (Kotlin now, so the phase-3
  backend inherits it).
- Rejections return `INVALID_IDENTIFIER` with the offending value, the rule and a hint.

**Path safety:**

- Every rendered path normalized and required to resolve inside the project root.
- Reject `..`, absolute paths, and Windows reserved names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`…,
  `LPT1`…) — zips get extracted on Windows too (§13).
- Reject paths differing only by case, which collide on case-insensitive filesystems.

**Resource caps, enforced at the plan stage:**

- 5,000 files, 50 MB uncompressed total, 5 MB per file, 10 s wall clock.
- Exceeding any cap yields `LIMIT_EXCEEDED` naming the cap and the observed value.

**Template sandbox confirmation** — assert the `kitbash-9` restrictions hold: no reflection, no
arbitrary method invocation, no user-path `include`, and a variable map of validated primitives
only.

**No configuration value ever becomes a shell command** — asserted by a test over the patch
appliers and the post-process stage.

## Out of scope

Rate limiting and auth (`kitbash-22`); the user-uploaded-recipe threat model (`kitbash-47`),
which changes the picture entirely and is its own project.

## Implementation notes

- Validate at the parse stage so a bad identifier never reaches a template, and again at path
  normalization so a bad *template* cannot produce a bad path either. Two layers, because the
  second one defends against recipes, not just users.
- Put the hostile-input corpus in a shared test fixture; `kitbash-39` and `kitbash-47` both want
  it.
- Cap enforcement must not require materializing the files it is counting — the plan stage knows
  sizes without rendering.

## Files and modules touched

`/server/core/**` (validators, caps), `/server/render/**`, `/server/core/src/test/**`.

## Done when

- A parameterized hostile-input test — traversal, reserved words, unicode confusables, oversized
  values, case-collision paths — produces typed rejections in every case and never writes a file
  outside the project root.
- Each cap has a test that trips it and asserts the observed value appears in the message.
