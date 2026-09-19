# kitbash-36-weekly-dependency-bump

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-35-full-matrix-sharding` · **Plan** §12, §13

## Goal

The job that stops the catalog from rotting: bump framework and library versions weekly, run the
full matrix, and open a merge request only when it is green.

## Context

§12 is unambiguous about why this exists: *scaffolding rots by default; this is the only thing
that stops it, and it is what makes a six-month-old preset still produce a modern project.* It is
the operational counterpart to §7's decision that presets track latest — a preset only tracks
something worth tracking if somebody keeps the catalog current.

§13 attaches a second duty to the same job: run generated dependency trees through a vulnerability
scan, *so this does not become an efficient distributor of known-vulnerable dependencies.*

## Scope

- Weekly job bumping `frameworkVersion` and library versions in recipe manifests, including the
  version catalog hook's inputs and the openapi-generator version.
- Runs the **full matrix** against the bumped catalog.
- Opens a merge request **only when green**, with a body listing every version delta and linking
  the matrix result.
- On failure, opens an **issue** naming the failing cell and the offending bump, rather than
  silently skipping the bump or opening a red MR.
- **Vulnerability scan** of the generated projects' dependency trees in the same job, with
  findings attached to the MR or issue (§13).
- Bumps are applied per recipe so a single incompatible library does not block every other update.
- The job's own schedule, scope and exclusions documented in `/docs/dependency-freshness.md`.

## Out of scope

Automatic merging. A human reviews and merges; the job's job is to make that review trivial.

## Implementation notes

- Recipe semver should be bumped alongside the framework version, because a pinned preset
  references `recipe@version` and needs a new version to move to (§7).
- Group bumps sensibly — a Spring Boot bump that requires a matching plugin bump must land as one
  MR or the matrix will fail for an uninteresting reason.
- Keep the diff readable: a bump MR touching only manifest version fields is reviewable in a
  minute, which is what makes the job sustainable.

## Files and modules touched

CI schedule config, `/server/verify/**` (bump + scan driver), `/recipes/*/recipe.yaml`,
`/docs/dependency-freshness.md`.

## Done when

- One bump merge request has been opened by the job, reviewed and merged.
- A deliberately incompatible bump produces an issue naming the failing cell, not a green MR.
- The vulnerability scan runs and its output is attached.
