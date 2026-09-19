# kitbash-40-history-fed-nightly-matrix

**Phase** 4 — Validation and polish · **Depends on** `kitbash-27-zip-cache-and-retention`, `kitbash-35-full-matrix-sharding` · **Plan** §10, §12

## Goal

Test the combinations people actually build, not only the ones someone thought to enumerate.

## Context

§12 calls this *the piece neither plan had*: feeding real history into the nightly run guarantees
the combinations people actually use are the ones under test. §10 supplies the input — popular
`selection_hash` values tell you which stacks to prioritize.

The gap this closes is specific. An enumerated matrix tests the catalog's cross-product, which is
not the same set as the stacks a team relies on; a combination that is unusual on paper but is the
house standard for one team deserves nightly coverage more than a cell nobody has ever generated.

## Scope

- Nightly input = every enumerated combination **plus the top 20 `selection_hash` values from real
  generation history** (§12).
- History-sourced cells are **labelled as such on the status page**, with their usage count, so it
  is obvious which failures affect real users.
- Cells whose recipes no longer exist are **skipped with a note**, not reported as failures — a
  removed recipe is a catalog change, not a regression.
- History-sourced cells count toward the nightly budget; if they push it past 20 minutes, sharding
  absorbs them (`kitbash-35`).
- **Only hashes, recipe ids and selections cross into the runner — never project or package
  names** (§10). The selection is needed to run the cell; the project name is not, and must be
  stripped.
- A weekly report of which history cells were added or dropped, so the set does not drift silently.

## Out of scope

No per-user notification when their stack fails; the status page and the wizard badges
(`kitbash-38`) are the channel.

## Implementation notes

- Deduplicate against the enumerated set before adding history cells — the top selection hashes
  will mostly already be enumerated, and the interesting ones are the few that are not.
- Strip `project_name` explicitly when constructing the cell rather than relying on it not being
  read; the privacy rule in §10 is worth enforcing structurally.
- Pull the popularity ranking from the metric or query added in `kitbash-27`, not from a fresh
  ad-hoc query, so there is one definition of "popular".

## Files and modules touched

`/server/verify/**` (history cell source), `/server/api/**` (popularity query),
`/verification/**` (status page labelling).

## Done when

- The nightly report lists history-sourced cells separately with usage counts.
- A stack that exists only in real usage is caught failing before a user reports it — demonstrated
  once with a deliberately broken recipe on a history-only cell.
- A name-leak test asserts no project name reaches the runner.
