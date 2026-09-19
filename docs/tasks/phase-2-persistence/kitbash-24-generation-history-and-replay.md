# kitbash-24-generation-history-and-replay

**Phase** 2 — Persistence · **Depends on** `kitbash-21-postgres-schema-flyway` · **Plan** §3, §7, §10

## Goal

Generations become receipts: every render is recorded with its lock, and any past generation
can be reproduced exactly or re-resolved against today's catalog.

## Context

§7 calls the lock the valuable part, and it is small — which is why nothing reproducible is lost
when an artifact expires (§10). The lock is also what makes *"this used to work"* debuggable:
diff two generation locks and the answer is usually right there.

The two replay modes are not a convenience pair, they answer different questions. **Exact**
asks "what did I ship in March?"; **current** asks "what would that same choice give me today?".

## Scope

- Every `/generate` writes a `generation` row: selection, **lock** (recipe id → exact version),
  catalog digest, selection hash, status, duration, size, project name.
- **API** — `GET /api/v1/generations`, `GET /api/v1/generations/{id}`,
  `GET /api/v1/generations/{id}/download`, `POST /api/v1/generations/{id}/replay`.
- **Replay modes** — `exact` (replay the lock) and `current` (re-resolve against today's
  catalog). The response states which mode ran and what changed if anything did.
- **Save as preset** from a history row, copying the lock forward (§10).
- **Keep** — sets `kept` and clears `expires_at`, exempting both the record and its artifact
  from the 30-day sweep (§10).
- **Lock diffing** — an endpoint or UI affordance to diff two generations' locks, since that is
  the stated debugging tool.
- **Privacy** — logs and metrics carry hashes and recipe ids only, never project or package
  names (§10). `project_name` is stored for display and deleted with the record.
- **Web** `history/` — list, download, replay (both modes), save as preset, keep.

## Out of scope

The object store and cache (`kitbash-27`) — until that lands, `artifact_key` may be null and
download re-renders. Retention sweeping is also `kitbash-27`.

## Implementation notes

- Record the generation row even on failure, with the failure status and the §14 error code.
  A history that only contains successes cannot answer "why did this break".
- Exact replay must fail loudly and usefully when a locked recipe version no longer exists in
  the repo, naming the missing `recipe@version`.
- Recording must not slow the streamed response: write the row after streaming completes, with
  duration and size captured from the stream itself.

## Files and modules touched

`/server/api/**` (generation service, controller), `/web/src/history/**`.

## Done when

- Two generations from different catalog digests can be diffed by their locks.
- An exact replay of a month-old generation reproduces its zip byte for byte.
- A name-leak test asserts no project or package name appears in logs or metrics.
