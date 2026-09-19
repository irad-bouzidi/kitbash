# Phase 2 — Persistence and product surface

State arrives: Postgres, SSO, presets, generation history with locks and replay, share links,
preview, and the zip cache. The engine does not change in this phase — it gains memory.

Two decisions from the plan govern everything here. First, the three nouns are never confused
(§3): **Recipe** is maintainer-owned and lives in git, **Preset** is user-owned and lives in
Postgres, **Generation** is a system-owned immutable receipt. Second, presets track latest
while generations carry a lock (§7) — that is what gives reproducibility without staleness.

**Phase exit test:** generate → save preset → cold reload → one-click regenerate produces an
identical zip from cache.

| # | Branch | Depends on |
| --- | --- | --- |
| 21 | [`kitbash-21-postgres-schema-flyway`](kitbash-21-postgres-schema-flyway.md) | 15 |
| 22 | [`kitbash-22-oidc-auth-and-rate-limits`](kitbash-22-oidc-auth-and-rate-limits.md) | 21 |
| 23 | [`kitbash-23-presets`](kitbash-23-presets.md) | 22, 16 |
| 24 | [`kitbash-24-generation-history-and-replay`](kitbash-24-generation-history-and-replay.md) | 21 |
| 25 | [`kitbash-25-share-links-and-url-selection`](kitbash-25-share-links-and-url-selection.md) | 16 |
| 26 | [`kitbash-26-preview-endpoint-and-viewer`](kitbash-26-preview-endpoint-and-viewer.md) | 12 |
| 27 | [`kitbash-27-zip-cache-and-retention`](kitbash-27-zip-cache-and-retention.md) | 24 |

25 and 26 are independent of the persistence chain and can be done at any point in the phase.
