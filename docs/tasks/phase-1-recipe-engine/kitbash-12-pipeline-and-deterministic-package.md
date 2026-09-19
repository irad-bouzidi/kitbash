# kitbash-12-pipeline-and-deterministic-package

**Phase** 1 — Recipe engine · **Depends on** `kitbash-9-render-pebble-sandbox`, `kitbash-11-hook-spi` · **Plan** §6, §4, §13

## Goal

The seven stages of §6, wired as separate testable functions rather than one method, with
byte-identical output guaranteed. The phase-0 hardcoded path is deleted in this MR.

## Context

§6 lists the stages precisely because the boundaries are what make the system testable and
what make `/preview` and `/validate` cheap: `/preview` runs stages 1–5, `/validate` runs 1–2.
That is what allows live conflict-checking on every option change.

Determinism is not a nice property here, it is load-bearing: the zip cache key (§10) and the
reproducibility guarantee (§7) both assume two identical selections produce byte-identical
zips.

## Scope

Seven functions, each independently callable and testable:

1. **Parse** — selection JSON → typed `Selection`; unknown recipe ids rejected here.
2. **Resolve** — `kitbash-8`, unchanged.
3. **Plan** — walk recipes in order, evaluate `when`, run hooks, collect file entries and patch
   ops. No rendering. **Resource caps enforced here**, before any bytes exist (§13).
4. **Render** — paths and bodies through Pebble; parallelizable.
5. **Patch** — apply ops in recipe order.
6. **Post-process** — git skeleton with one initial commit, file modes (`gradlew` = 0755),
   line endings, entry sort.
7. **Package** — stream to `ZipOutputStream` with fixed timestamps.

Plus:

- Entry points exposed for partial pipelines: `preview()` = 1–5, `validate()` = 1–2.
- The phase-0 substitution class and hardcoded generate path are **deleted**, not left beside
  the new engine.
- The determinism suite becomes first-class: for N representative selections, generate twice
  and assert byte equality, including across separate JVM runs.

## Out of scope

No HTTP surface changes beyond pointing `/generate` at the new pipeline; `/preview` and
`/validate` endpoints land in `kitbash-26` and `kitbash-15`.

## Implementation notes

- Each stage takes the previous stage's output as its only input. If a stage needs something
  earlier, thread it through the type rather than reaching for a field — that is how one method
  reassembles itself out of seven.
- Enforcing caps at plan time is the point of having a plan stage: the failure happens before
  rendering cost is paid and before a single byte streams to the client.
- The git skeleton must itself be deterministic: fixed author, fixed committer, fixed
  timestamp, or the zip hash changes on every request.

## Files and modules touched

`/server/core/**`, `/server/render/**`, `/server/api/**` (wiring + deletion of phase-0 path).

## Done when

- Each stage has unit tests that run without Spring.
- The byte-equality suite passes across two JVM invocations.
- The phase-0 hardcoded generator no longer exists in the tree.
