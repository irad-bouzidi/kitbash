# Phase 1 — Recipe engine

The phase that decides whether the project survives (§4, §19). Everything hardcoded in
phase 0 becomes data: recipes, a resolver, a patch engine, a metadata endpoint and a wizard
that knows nothing about Spring Boot or React. The verification matrix stands up here too,
even at four cells, because retrofitting it later means retrofitting trust.

The single rule that governs every task in this phase: **never store a template per stack
combination**, and never branch on a technology name outside a recipe directory.

**Phase exit test:** four matrix cells green nightly, and adding a recipe requires no
frontend change.

| # | Branch | Depends on |
| --- | --- | --- |
| 6 | [`kitbash-6-core-domain-model`](kitbash-6-core-domain-model.md) | 1 |
| 7 | [`kitbash-7-recipe-manifest-and-loader`](kitbash-7-recipe-manifest-and-loader.md) | 6 |
| 8 | [`kitbash-8-resolver`](kitbash-8-resolver.md) | 7 |
| 9 | [`kitbash-9-render-pebble-sandbox`](kitbash-9-render-pebble-sandbox.md) | 6 |
| 10 | [`kitbash-10-patch-engine`](kitbash-10-patch-engine.md) | 6 |
| 11 | [`kitbash-11-hook-spi`](kitbash-11-hook-spi.md) | 8, 10 |
| 12 | [`kitbash-12-pipeline-and-deterministic-package`](kitbash-12-pipeline-and-deterministic-package.md) | 9, 11 |
| 13 | [`kitbash-13-recipes-extract-phase0-stack`](kitbash-13-recipes-extract-phase0-stack.md) | 12 |
| 14 | [`kitbash-14-recipe-frontend-react-vite`](kitbash-14-recipe-frontend-react-vite.md) | 13 |
| 15 | [`kitbash-15-metadata-and-validate-endpoints`](kitbash-15-metadata-and-validate-endpoints.md) | 13 |
| 16 | [`kitbash-16-metadata-driven-wizard`](kitbash-16-metadata-driven-wizard.md) | 15 |
| 17 | [`kitbash-17-cli-module`](kitbash-17-cli-module.md) | 12 |
| 18 | [`kitbash-18-verification-runner`](kitbash-18-verification-runner.md) | 14, 17 |
| 19 | [`kitbash-19-reference-equality-test`](kitbash-19-reference-equality-test.md) | 13, 14 |
| 20 | [`kitbash-20-input-validation-and-caps`](kitbash-20-input-validation-and-caps.md) | 12 |

Tasks 9 and 10 are independent of 7 and 8 and can run in parallel with them.
