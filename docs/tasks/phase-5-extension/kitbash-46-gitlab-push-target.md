# kitbash-46-gitlab-push-target

**Phase** 5 — Extension · **Depends on** `kitbash-24-generation-history-and-replay` · **Plan** §17, §18

## Goal

Push the generated repository straight into a GitLab group instead of downloading a zip.

## Context

§18 explains the deferral rather than dismissing the feature: *the zip path must be excellent
first, and the API surface changes when a push target exists.* Both halves matter. A push target
built early would have competed for attention with the engine; built now, it reuses everything —
the same deterministic tree, the same initial commit, the same generation record.

The constraint to hold onto: the zip path stays the default and must not be degraded by this.

## Scope

- OAuth-scoped GitLab integration; the user authorizes once and picks a group and a project name.
- The server creates the project and pushes **the same initial commit the zip carries** — not a
  re-render, not a different commit. Determinism is what makes that claim checkable.
- Explicit handling for the failure modes that will actually occur: name already taken,
  insufficient permission in the group, group not visible to the token, push rejected by a group
  policy. Each gets a §14 envelope with a hint.
- Generation history records the pushed project URL alongside the usual lock and digest.
- The wizard's bottom bar gains a push action beside Generate; Generate remains the primary.
- Scope of the OAuth token kept minimal and documented — this feature creates repositories, which
  is a privilege worth naming explicitly in the docs.

## Out of scope

GitHub as a push target, unless and until someone asks. Pushing to an existing repository.
Branch/MR creation beyond the initial commit.

## Implementation notes

- Push server-side from the generation pipeline's output, not by re-running generation for the
  push path; two code paths producing "the same" commit is how they stop being the same.
- A partially completed push (project created, push failed) must be reported as exactly that, with
  the created project named, rather than rolled back silently or reported as a generic failure.

## Files and modules touched

`/server/api/**` (GitLab client, push service, OAuth), `/web/src/wizard/**`,
`/docs/gitlab-push.md`.

## Done when

One click produces a GitLab project whose initial commit is byte-identical to the zip's initial
commit, verified by a test that generates both and compares the commit trees.
