# About this directory

This is a **reference project** for the kitbash generator, not a template. It installs, builds,
tests and lints on its own, and it is the source `recipes/frontend-react-vite` was extracted from
(§4).

`README.md` is deliberately not about any of that: it is the README a generated project gets, so it
describes only the stack that was selected. Anything true of this directory *because* it is a
reference project — including this file — lives here instead, and is listed under
`excludeFromExtraction` in [`reference-variables.json`](reference-variables.json).

This project is also the **standalone-frontend cell** (§18). No backend is selected, so the recipe
has to resolve, generate and build on its own, with the API origin configurable. That case is the
one most likely to break silently, which is why it is a reference project rather than only a matrix
row.

The maintenance workflow, and why the direction matters, is the same as the backend reference
project's: edit this project, run `./gradlew :verify:test` from `server/`, and port the diff into
the recipe. The full version, including the justification for every path the equality test
ignores, is in [`docs/reference-projects.md`](../../docs/reference-projects.md).
