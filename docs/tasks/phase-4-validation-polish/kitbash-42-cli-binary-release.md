# kitbash-42-cli-binary-release

**Phase** 4 — Validation and polish · **Depends on** `kitbash-17-cli-module`, `kitbash-39-structured-error-surface` · **Plan** §6, §12, §17

## Goal

Ship `cli` as an artifact people can install, so offline generation is a product feature rather
than an internal test harness.

## Context

§17 lists "CLI binary published" as part of phase 4's scope. The module has existed since
`kitbash-17` because the verification matrix runs through it; this task is what makes it usable by
someone who is not the matrix.

The constraint worth preserving is the one that made the module valuable in the first place: it
depends on `core`, `catalog` and `render` only, and boots no Spring context (§6).

## Scope

- A native or self-contained distribution, versioned alongside the **catalog digest it embeds** —
  a CLI carries its catalog, so which catalog it carries is part of its identity.
- `kitbash --version` reports both the tool version and the embedded catalog digest (§8's
  rationale for exposing the digest applies here too: it is what makes a bug report actionable).
- Published from CI on tag, for the platforms the team actually uses.
- Install instructions in the repo README, including how to point at a different recipe tree with
  `--catalog`.
- **Smoke test in CI**: download the published artifact into a clean container, generate a project
  offline, and build it. Publishing something nobody has installed is not publishing.
- Errors printed as the §14 envelope in JSON on stderr, with a human-readable line on top when
  stdout is a TTY.

## Out of scope

Package-manager distribution (Homebrew, apt) unless it is trivial; `npx create-stack` is a
separate product surface (`kitbash-44`).

## Implementation notes

- Embedding the catalog makes the binary reproducible and offline-capable, which is the point —
  but it also means a stale binary emits a stale catalog. Make `--version` and the generated
  README both state the digest so that is visible rather than surprising.
- The smoke test should run in a container with **no JDK installed** if the distribution claims to
  be self-contained. Testing it on a developer machine proves nothing.

## Files and modules touched

`/server/cli/**` (packaging), CI release pipeline, repo README.

## Done when

A developer with no JDK setup installs the published binary, generates a project offline, and
builds it — executed by the CI smoke test on every release.
