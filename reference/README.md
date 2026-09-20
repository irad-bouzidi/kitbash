# `/reference` — real projects the recipes derive from

Each subdirectory is an ordinary, compiling, test-passing project that a developer would be
happy to inherit. They are not templates: no placeholders, no `.peb` files, no Gradle
relationship to `/server`. You can `cd` into one and build it.

They exist because editing a real project beats editing template soup (§4). Recipe content is
extracted from here, and a CI equality test
([`kitbash-19`](../docs/tasks/phase-1-recipe-engine/kitbash-19-reference-equality-test.md))
asserts that generating with the reference's own variable set reproduces the reference —
so the two cannot drift.

| Project | Selection it is generated from | Covers |
| --- | --- | --- |
| [`spring-boot-java-gradle-layered`](spring-boot-java-gradle-layered) | backend + Gradle + Postgres + containers + CI | The JVM half, and the vertical slice |
| [`react-vite-ts`](react-vite-ts) | frontend + containers, **no backend** | The standalone-frontend case §18 settles, which is the one most likely to break silently |

Each carries a `reference-variables.json` naming the selection it is generated from and the
literals that became variables, and a `REFERENCE.md` explaining the workflow. Both are excluded
from extraction: a generated project should not explain that it is a reference for a generator.

**In a full-stack project the backend occupies the root and the frontend lives under
`frontend/`.** §15's own layout puts the generated typed client at `frontend/src/lib/api`, the
Gradle wrapper has to sit at the root of the project it builds, and a fixed home for the frontend
means that adding a backend to a standalone one never moves a file.

Every reference project must clear the full §15 bar: working vertical slice, passing tests
including Testcontainers, non-root multi-stage container, `.env.example`, health endpoint,
structured logging, format check, and a README whose first screen states the one start command.
