# 0002 — CI on both GitLab and GitHub

**Status** Accepted · **Date** 2026-09-19 · **Plan** §17 (phase 0), §11

## Context

The plan and the task backlog were written against GitLab: `kitbash-1` asks for a
`.gitlab-ci.yml`, `kitbash-5` adds the generated-build gate to it, and phase 5 pushes
generated repositories into a GitLab group. The repository, however, is hosted on GitHub, and
merge requests are GitHub pull requests. A `.gitlab-ci.yml` alone would be an inert file: no
pipeline would run, and every "green CI" gate in the backlog would be unverifiable.

Note this is about **kitbash's own** pipeline. The `ci-gitlab` and `ci-github` *recipes*,
which emit pipelines into generated projects, are a separate axis of the catalog
(`kitbash-34`) and unaffected by this decision.

## Decision

Maintain both, and keep both thin.

- `.github/workflows/*` is the pipeline that gates merges today.
- `.gitlab-ci.yml` stays in step with it, job for job, so the plan's GitLab target remains a
  move rather than a rewrite.

Every job in both files is a single command whose logic lives in the repository — a Gradle
task, a script under `verification/` — never in the YAML. The two files should read as
transcriptions of the same list.

## Consequences

Two files change whenever a job is added, and they can drift. That is the accepted cost, and
the mitigation is the thinness rule above: if a job needs logic, the logic goes into a script
that both providers call, so drift is limited to which script is invoked.

The alternative considered and rejected was writing only `.gitlab-ci.yml` as the backlog
specifies. It keeps the documents literally accurate at the price of an unverifiable pipeline
in every task from `kitbash-1` onward, which is a poor trade for a project whose central claim
is that its output is proven to build.
