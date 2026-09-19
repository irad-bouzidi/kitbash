# kitbash-27-zip-cache-and-retention

**Phase** 2 — Persistence · **Depends on** `kitbash-24-generation-history-and-replay` · **Plan** §10, §18

## Goal

Cash in determinism as a cache, and adopt one retention number across everything that expires.

## Context

§10 links the two halves of this task. The cache is only safe because rendering is
deterministic — a cache hit is served verbatim, which would be a correctness bug in a generator
whose output varied. And the retention story is deliberately uniform: **30 days** for cached
zips, generation records and verification logs alike, because *"anything older than a month is
gone unless you kept it"* is a sentence people can remember, and three separate policies is
three things nobody can recall.

Nothing reproducible is lost at expiry, because the lock is the valuable part and it is small.

## Scope

- **Object store** — S3-compatible, MinIO locally (§5), content-addressed keys
  `sha256(catalogDigest + canonicalSelection)` (§10).
- **Cache lookup before render**; a hit is served verbatim and records a generation row that
  points at the existing artifact.
- **Bounded in-memory LRU** for key → object mapping, so a hit does not always cost a round trip.
- **Retention, 30 days uniformly** (§10, §18):
  - cached zips expire by S3 lifecycle rule,
  - generation rows and verification logs expire by a nightly sweep on `expires_at`.
- **Keep** — `kept` rows are exempt; a kept row's artifact may still be expired after a year by
  the sweep, and the row then replays by re-rendering. This is the documented behaviour, not a
  degradation.
- **Popular selections** — `selection_hash` frequencies exposed as a metric, which is what
  `kitbash-40` feeds into the nightly matrix.
- Cache hits **do not consume rate-limit budget** (§13) — wire the limiter after the lookup.

## Out of scope

No cross-region replication, no CDN, no artifact signing. Verification logs are stored by
`kitbash-37`; this task only defines and applies their expiry.

## Implementation notes

- Write the sweep as an idempotent job that can run twice without harm, and log counts by
  category so retention is observable rather than assumed.
- Guard against the cache serving output from a different catalog digest: the digest is part of
  the key, so this is a test to write, not a risk to manage.
- Measure and publish cache hit rate from day one (§14); it is the number that tells you whether
  determinism is actually holding in production.

## Files and modules touched

`/server/api/**` (cache service, sweep job, object store client), `/compose.yaml` (MinIO),
metrics registration.

## Done when

The **phase exit test** passes: generate → save preset → cold reload → one-click regenerate
returns an identical zip served from cache, with the cache-hit metric incremented and no
rate-limit budget consumed. The sweep is proven by a test that ages rows and asserts what
survives.
