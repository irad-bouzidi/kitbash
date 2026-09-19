# Phase 0 — Walking skeleton

One hardcoded stack, end to end, no recipe engine. The point of this phase is a proven path
from a button click to a zip that builds, and a CI job that keeps proving it. Almost
everything written here is expected to be deleted in phase 1 — except the reference project,
the deterministic zip writer and the ecosystem container, which are permanent.

Stack for this phase: Java 21 + Spring Boot + Gradle Kotlin DSL + Postgres/Flyway, layered
architecture, no frontend in the *generated* project. Plan §17 (Phase 0), §18.

**Phase exit test:** the downloaded zip unzips and `./gradlew build` passes, executed in CI.

| # | Branch | Depends on |
| --- | --- | --- |
| 1 | [`kitbash-1-repo-scaffold`](kitbash-1-repo-scaffold.md) | — |
| 2 | [`kitbash-2-reference-spring-java-layered`](kitbash-2-reference-spring-java-layered.md) | 1 |
| 3 | [`kitbash-3-hardcoded-generate-endpoint`](kitbash-3-hardcoded-generate-endpoint.md) | 2 |
| 4 | [`kitbash-4-web-shell-generate-button`](kitbash-4-web-shell-generate-button.md) | 3 |
| 5 | [`kitbash-5-ci-generated-build-gate`](kitbash-5-ci-generated-build-gate.md) | 3 |

Tasks 4 and 5 are independent of each other and can be done in either order.
