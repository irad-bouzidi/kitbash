# kitbash-35-full-matrix-sharding

**Phase** 3 — Catalog breadth · **Depends on** `kitbash-33-openapi-typed-client`, `kitbash-34-ci-github-actions` · **Plan** §11, §12, §18

## Goal

Scale verification from four cells to the full enumeration — roughly 96 reachable combinations —
and keep it inside the nightly time budget.

## Context

§11 calls ~96 combinations *a sane matrix to verify and real proof the composition model works*.
§12 sets the budget: nightly under 20 minutes warm, sharded by ecosystem if it creeps.

§18 also grants one sampling exemption, and it is worth honouring precisely: the frontend-only
axis is **sampled rather than enumerated**, because supporting a standalone frontend costs one
conditional and should not double the matrix.

## Scope

- Enumerate all reachable combinations from the catalog rather than maintaining a hand-written
  list — the enumeration is derived from the recipes, so a new recipe expands the matrix
  automatically.
- Sample the frontend-only axis; document the sampling rule where the enumeration lives.
- **Shard by ecosystem**, run shards in parallel, and keep dependency caches warm through the
  proxy.
- Publish **per-cell timing** so the slowest cells are visible before the budget is breached.
- Status page shows pass/fail and duration per cell, and feeds the verification badges the
  metadata endpoint exposes (`kitbash-38`).
- The merge-request job keeps its ~8 representative cells and its 10-minute budget; the
  representative set is chosen to cover each axis at least once and is documented.
- Failure reporting unchanged: exact selection JSON plus a one-line local reproduction.

## Out of scope

History-fed cells (`kitbash-40`) and on-demand user runs (`kitbash-37`) — both plug into this
runner later.

## Implementation notes

- Deriving the enumeration from the catalog means an accidentally over-permissive recipe can
  explode the matrix. Add an assertion on the expected cell count so a jump from 96 to 900 fails
  loudly instead of blowing the budget silently.
- Shard assignment must be deterministic so a flaky cell can be traced to a shard and rerun in
  isolation.
- Track warm versus cold nightly durations separately; the 20-minute budget is a warm number.

## Files and modules touched

`/server/verify/**` (enumeration, sharding), `/verification/**` (status page), CI schedules.

## Done when

- The nightly full matrix completes green under 20 minutes warm.
- Per-cell timings are on the status page.
- The cell-count assertion is in place and documented.
