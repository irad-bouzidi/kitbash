# kitbash-37-on-demand-verify-job

**Phase** 4 — Validation and polish · **Depends on** `kitbash-35-full-matrix-sharding`, `kitbash-22-oidc-auth-and-rate-limits` · **Plan** §8, §12, §18

## Goal

The third trigger on the existing verification runner: any authenticated user can ask "does my
combination actually build?" and get an answer — instantly if someone already asked.

## Context

This is the **one** place in the system where the 202 pattern exists (§8). Generation is
synchronous and streamed because it takes tens of milliseconds; build validation genuinely takes
minutes, so it is the single case that earns a job.

§18 settles who may run it — every authenticated user — and §12 explains why that is affordable:
*not because it is cheap, but because the work is deduplicated.* A run is keyed by
`(selection_hash, catalog_digest)`, so the second person to verify the house stack gets the first
person's result instantly, and the nightly matrix has already pre-populated every enumerated cell.
What is left is the genuinely novel combination, which is exactly the case worth spending a
container on.

## Scope

- **`POST /api/v1/verify`** — returns **202 + job id**, or **200 with the existing run** when that
  selection was already verified against this catalog digest.
- **`GET /api/v1/verify/{id}`** — `PENDING | RUNNING | PASSED | FAILED` plus logs.
- **Dedupe** on `(selection_hash, catalog_digest)`, enforced by the partial unique index from
  `kitbash-21` so it is correct under concurrency, not merely likely.
- **Controls** (§12, §13):
  - one concurrent run per user,
  - a global worker pool of four,
  - a 15-minute hard timeout,
  - a queue-depth cap returning **429 with the current depth**, rather than silently queueing for
    an hour.
- Runs on the same runner and images as the matrix: disposable containers, no network beyond the
  dependency proxy, CPU and memory caps. **Never on the API host** (§12).
- Logs written to the object store with the 30-day expiry (§10), served through the API rather
  than by a direct bucket link.
- Open to every authenticated user; throttled by concurrency rather than request count (§13).

## Out of scope

No priority queue, no paid tiers, no per-user quotas beyond the concurrency rule.

## Implementation notes

- The 200-with-existing-run path is the common case — make it the fast path, answered from the
  database without touching the queue.
- A `failed` run is *not* covered by the dedupe index (only `pending|running|passed` are), which
  means a user can retry a failure after a fix lands. That asymmetry is deliberate; encode it in a
  test so nobody "fixes" it.
- Surface the queue depth in the 429 body and in metrics; a user who knows the depth will wait,
  and one who does not will retry in a loop.

## Files and modules touched

`/server/api/**` (verify controller, job service), `/server/verify/**` (single-cell entry point),
`/web/src/wizard/**` (call site is wired in `kitbash-38`).

## Done when

- Two users verifying the same stack produce **one** container run and two results, the second
  instant.
- A queue flood returns 429 with the current depth instead of hanging.
- A run exceeding 15 minutes is killed and reported as failed with the timeout named.
