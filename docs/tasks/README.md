# Kitbash — task backlog

Every task in this backlog is one branch, one merge request, one reviewable unit of work.
The branch name *is* the task id. Tasks are numbered globally and never renumbered;
if a task is dropped, its number is retired rather than reused.

Source of truth for scope: [`../../Project Bootstrapper — Unified Plan.md`](../../Project%20Bootstrapper%20—%20Unified%20Plan.md).
Section references below (`§4`, `§12`, …) point into that document.

## Branch naming

```
kitbash-<n>-<kebab-slug>
```

`<n>` is the task number from this backlog, `<slug>` is short and names the deliverable,
not the activity (`kitbash-8-resolver`, not `kitbash-8-write-resolver-code`).

Sub-branches for work that has to be split mid-flight append a suffix:
`kitbash-10-patch-engine.2`. They merge into the parent branch, not into `main`.

## Layout

One directory per phase, one file per task, named after the branch:

```
docs/tasks/
  README.md                            # this file
  phase-1-recipe-engine/
    README.md                          # phase goal, exit test, task order
    kitbash-8-resolver.md              # one task = one branch = one merge request
    ...
```

Each task file carries: goal, context (why the plan asks for it, with section references),
scope, out of scope, implementation notes, files and modules touched, and a single
**Done when** that is a command or an observable fact rather than a feeling.

## Phases

| Phase | File | Tasks | Exit test |
| --- | --- | --- | --- |
| 0 — Walking skeleton | [phase-0-walking-skeleton/](phase-0-walking-skeleton/README.md) | 1–5 | Downloaded zip unzips and `./gradlew build` passes, executed in CI |
| 1 — Recipe engine | [phase-1-recipe-engine/](phase-1-recipe-engine/README.md) | 6–20 | Four matrix cells green nightly; adding a recipe requires no frontend change |
| 2 — Persistence & product surface | [phase-2-persistence/](phase-2-persistence/README.md) | 21–27 | Generate → save preset → cold reload → one-click regenerate yields an identical zip from cache |
| 3 — Catalog breadth | [phase-3-catalog-breadth/](phase-3-catalog-breadth/README.md) | 28–36 | ~96 cells green nightly under 20 min; a backend DTO change breaks frontend typecheck in a generated project |
| 4 — Validation & polish | [phase-4-validation-polish/](phase-4-validation-polish/README.md) | 37–42 | A user can verify their own combination from the UI and read the build log |
| 5 — Extension | [phase-5-extension/](phase-5-extension/README.md) | 43–47 | Per-task; no single gate |

## Full branch list

| # | Branch | Phase |
| --- | --- | --- |
| 1 | [`kitbash-1-repo-scaffold`](phase-0-walking-skeleton/kitbash-1-repo-scaffold.md) | 0 |
| 2 | [`kitbash-2-reference-spring-java-layered`](phase-0-walking-skeleton/kitbash-2-reference-spring-java-layered.md) | 0 |
| 3 | [`kitbash-3-hardcoded-generate-endpoint`](phase-0-walking-skeleton/kitbash-3-hardcoded-generate-endpoint.md) | 0 |
| 4 | [`kitbash-4-web-shell-generate-button`](phase-0-walking-skeleton/kitbash-4-web-shell-generate-button.md) | 0 |
| 5 | [`kitbash-5-ci-generated-build-gate`](phase-0-walking-skeleton/kitbash-5-ci-generated-build-gate.md) | 0 |
| 6 | [`kitbash-6-core-domain-model`](phase-1-recipe-engine/kitbash-6-core-domain-model.md) | 1 |
| 7 | [`kitbash-7-recipe-manifest-and-loader`](phase-1-recipe-engine/kitbash-7-recipe-manifest-and-loader.md) | 1 |
| 8 | [`kitbash-8-resolver`](phase-1-recipe-engine/kitbash-8-resolver.md) | 1 |
| 9 | [`kitbash-9-render-pebble-sandbox`](phase-1-recipe-engine/kitbash-9-render-pebble-sandbox.md) | 1 |
| 10 | [`kitbash-10-patch-engine`](phase-1-recipe-engine/kitbash-10-patch-engine.md) | 1 |
| 11 | [`kitbash-11-hook-spi`](phase-1-recipe-engine/kitbash-11-hook-spi.md) | 1 |
| 12 | [`kitbash-12-pipeline-and-deterministic-package`](phase-1-recipe-engine/kitbash-12-pipeline-and-deterministic-package.md) | 1 |
| 13 | [`kitbash-13-recipes-extract-phase0-stack`](phase-1-recipe-engine/kitbash-13-recipes-extract-phase0-stack.md) | 1 |
| 14 | [`kitbash-14-recipe-frontend-react-vite`](phase-1-recipe-engine/kitbash-14-recipe-frontend-react-vite.md) | 1 |
| 15 | [`kitbash-15-metadata-and-validate-endpoints`](phase-1-recipe-engine/kitbash-15-metadata-and-validate-endpoints.md) | 1 |
| 16 | [`kitbash-16-metadata-driven-wizard`](phase-1-recipe-engine/kitbash-16-metadata-driven-wizard.md) | 1 |
| 17 | [`kitbash-17-cli-module`](phase-1-recipe-engine/kitbash-17-cli-module.md) | 1 |
| 18 | [`kitbash-18-verification-runner`](phase-1-recipe-engine/kitbash-18-verification-runner.md) | 1 |
| 19 | [`kitbash-19-reference-equality-test`](phase-1-recipe-engine/kitbash-19-reference-equality-test.md) | 1 |
| 20 | [`kitbash-20-input-validation-and-caps`](phase-1-recipe-engine/kitbash-20-input-validation-and-caps.md) | 1 |
| 21 | [`kitbash-21-postgres-schema-flyway`](phase-2-persistence/kitbash-21-postgres-schema-flyway.md) | 2 |
| 22 | [`kitbash-22-oidc-auth-and-rate-limits`](phase-2-persistence/kitbash-22-oidc-auth-and-rate-limits.md) | 2 |
| 23 | [`kitbash-23-presets`](phase-2-persistence/kitbash-23-presets.md) | 2 |
| 24 | [`kitbash-24-generation-history-and-replay`](phase-2-persistence/kitbash-24-generation-history-and-replay.md) | 2 |
| 25 | [`kitbash-25-share-links-and-url-selection`](phase-2-persistence/kitbash-25-share-links-and-url-selection.md) | 2 |
| 26 | [`kitbash-26-preview-endpoint-and-viewer`](phase-2-persistence/kitbash-26-preview-endpoint-and-viewer.md) | 2 |
| 27 | [`kitbash-27-zip-cache-and-retention`](phase-2-persistence/kitbash-27-zip-cache-and-retention.md) | 2 |
| 28 | [`kitbash-28-build-tool-maven`](phase-3-catalog-breadth/kitbash-28-build-tool-maven.md) | 3 |
| 29 | [`kitbash-29-backend-spring-kotlin`](phase-3-catalog-breadth/kitbash-29-backend-spring-kotlin.md) | 3 |
| 30 | [`kitbash-30-architectures-hexagonal-modular`](phase-3-catalog-breadth/kitbash-30-architectures-hexagonal-modular.md) | 3 |
| 31 | [`kitbash-31-feature-auth-jwt`](phase-3-catalog-breadth/kitbash-31-feature-auth-jwt.md) | 3 |
| 32 | [`kitbash-32-feature-observability`](phase-3-catalog-breadth/kitbash-32-feature-observability.md) | 3 |
| 33 | [`kitbash-33-openapi-typed-client`](phase-3-catalog-breadth/kitbash-33-openapi-typed-client.md) | 3 |
| 34 | [`kitbash-34-ci-github-actions`](phase-3-catalog-breadth/kitbash-34-ci-github-actions.md) | 3 |
| 35 | [`kitbash-35-full-matrix-sharding`](phase-3-catalog-breadth/kitbash-35-full-matrix-sharding.md) | 3 |
| 36 | [`kitbash-36-weekly-dependency-bump`](phase-3-catalog-breadth/kitbash-36-weekly-dependency-bump.md) | 3 |
| 37 | [`kitbash-37-on-demand-verify-job`](phase-4-validation-polish/kitbash-37-on-demand-verify-job.md) | 4 |
| 38 | [`kitbash-38-wizard-verification-badges`](phase-4-validation-polish/kitbash-38-wizard-verification-badges.md) | 4 |
| 39 | [`kitbash-39-structured-error-surface`](phase-4-validation-polish/kitbash-39-structured-error-surface.md) | 4 |
| 40 | [`kitbash-40-history-fed-nightly-matrix`](phase-4-validation-polish/kitbash-40-history-fed-nightly-matrix.md) | 4 |
| 41 | [`kitbash-41-metrics-and-structured-logs`](phase-4-validation-polish/kitbash-41-metrics-and-structured-logs.md) | 4 |
| 42 | [`kitbash-42-cli-binary-release`](phase-4-validation-polish/kitbash-42-cli-binary-release.md) | 4 |
| 43 | [`kitbash-43-recipe-authoring-sdk`](phase-5-extension/kitbash-43-recipe-authoring-sdk.md) | 5 |
| 44 | [`kitbash-44-create-stack-npx`](phase-5-extension/kitbash-44-create-stack-npx.md) | 5 |
| 45 | [`kitbash-45-recipe-react-native-expo`](phase-5-extension/kitbash-45-recipe-react-native-expo.md) | 5 |
| 46 | [`kitbash-46-gitlab-push-target`](phase-5-extension/kitbash-46-gitlab-push-target.md) | 5 |
| 47 | [`kitbash-47-user-contributed-recipes`](phase-5-extension/kitbash-47-user-contributed-recipes.md) | 5 |
| 48 | [`kitbash-48-contributed-recipe-generation`](phase-5-extension/kitbash-48-contributed-recipe-generation.md) | 5 |

## Definition of done, every task

1. Code compiles; `./gradlew check` and (where the task touches `web/`) `pnpm lint && pnpm test` are green.
2. New logic has tests at the level it lives: pure functions in `core` get unit tests, endpoints get slice tests, generated output gets a verification cell.
3. Nothing new is hardcoded on a technology name outside a recipe directory (§19).
4. Determinism is preserved: the byte-identical-zip test still passes.
5. The task's own "Done when" line is demonstrably true, stated in the MR description with the command that shows it.
