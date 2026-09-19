# Phase 3 — Catalog breadth

*Breadth added to a sound engine is a weekend; breadth added to an unsound one is the reason
these projects die* (§19). Phase 1 and 2 built the engine; this phase spends it.

Every task here should be **recipes plus reference projects plus matrix rows**. A task in this
phase that needs a change to `core`, `render` or `web` is a signal worth raising in its merge
request — sometimes the answer is that the manifest format is genuinely missing a feature (§4),
but it should never pass unremarked.

**Phase exit test:** ~96 cells green nightly under 20 minutes, and a backend DTO change breaks
frontend typecheck in a generated project.

| # | Branch | Depends on |
| --- | --- | --- |
| 28 | [`kitbash-28-build-tool-maven`](kitbash-28-build-tool-maven.md) | 13 |
| 29 | [`kitbash-29-backend-spring-kotlin`](kitbash-29-backend-spring-kotlin.md) | 28 |
| 30 | [`kitbash-30-architectures-hexagonal-modular`](kitbash-30-architectures-hexagonal-modular.md) | 29 |
| 31 | [`kitbash-31-feature-auth-jwt`](kitbash-31-feature-auth-jwt.md) | 30 |
| 32 | [`kitbash-32-feature-observability`](kitbash-32-feature-observability.md) | 31 |
| 33 | [`kitbash-33-openapi-typed-client`](kitbash-33-openapi-typed-client.md) | 32 |
| 34 | [`kitbash-34-ci-github-actions`](kitbash-34-ci-github-actions.md) | 28 |
| 35 | [`kitbash-35-full-matrix-sharding`](kitbash-35-full-matrix-sharding.md) | 33, 34 |
| 36 | [`kitbash-36-weekly-dependency-bump`](kitbash-36-weekly-dependency-bump.md) | 35 |

34 is independent of the 29→33 chain and can run in parallel with it.
