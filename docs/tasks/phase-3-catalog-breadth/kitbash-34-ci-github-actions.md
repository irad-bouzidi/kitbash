# kitbash-34-ci-github-actions

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-28-build-tool-maven` · **Plan** §11, §15

## Goal

A second CI provider, so `ci` is a slot like every other — and a check that what is emitted is a
pipeline that actually runs, not merely a file that parses.

## Context

§15 requires every generated project to ship a CI pipeline for the chosen provider that builds,
tests, lints and produces a container image. Adding the second provider is what stops the CI
recipe from quietly being "the GitLab file" with a configuration flag.

The failure mode specific to CI recipes is that they look fine forever: a rendered workflow file
is valid YAML, gets committed, and nobody notices it never ran. So this task includes linting
the emitted pipeline, and running one for real.

## Scope

- **Recipe** `/recipes/ci-github` — `provides: [ci]`,
  `conflictsWith: [ci-gitlab]`, emitting a workflow that builds, tests, lints and produces a
  container image, matching the GitLab recipe capability for capability.
- Shared fragments where the steps genuinely match across providers; honest duplication where
  they differ. A single templated file rendering both providers' syntaxes would be template soup.
- Build-tool and language awareness through `when`, reusing the mechanism from `kitbash-28`.
- **A matrix cell per CI provider that lints the emitted pipeline file** — `actionlint` for
  GitHub, the GitLab CI lint API for GitLab — rather than only checking it renders.
- One emitted GitHub workflow exercised against a real runner, once, to prove it is not merely
  well-formed.

## Out of scope

Jenkins and Drone remain deferred (§11). No deployment stages — the plan's non-goals exclude
provisioning real infrastructure (§1); the pipeline builds an image, it does not ship it.

## Implementation notes

- Keep secrets out of the emitted pipeline: reference variables by name, document them in
  `.env.example`, and never emit a placeholder that looks like a credential.
- The container image step should use the same multi-stage Dockerfile the infra recipe emits, not
  a second definition — otherwise the two drift.

## Files and modules touched

`/recipes/ci-github/**`, `/recipes/ci-gitlab/**` (shared fragments), `/reference/**`,
`/verification/cells/**`.

## Done when

- Both providers emit a pipeline that passes its own linter in a matrix cell.
- The emitted GitHub workflow has been run once on a real runner and passed, with the run linked
  in the MR.
