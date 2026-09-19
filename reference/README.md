# `/reference` — real projects the recipes derive from

Each subdirectory is an ordinary, compiling, test-passing project that a developer would be
happy to inherit. They are not templates: no placeholders, no `.peb` files, no Gradle
relationship to `/server`. You can `cd` into one and build it.

They exist because editing a real project beats editing template soup (§4). Recipe content is
extracted from here, and a CI equality test
([`kitbash-19`](../docs/tasks/phase-1-recipe-engine/kitbash-19-reference-equality-test.md))
asserts that generating with the reference's own variable set reproduces the reference —
so the two cannot drift.

The first one is
[`kitbash-2-reference-spring-java-layered`](../docs/tasks/phase-0-walking-skeleton/kitbash-2-reference-spring-java-layered.md).

Every reference project must clear the full §15 bar: working vertical slice, passing tests
including Testcontainers, non-root multi-stage container, `.env.example`, health endpoint,
structured logging, format check, and a README whose first screen states the one start command.
