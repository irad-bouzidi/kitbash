# About this directory

This is a **reference project** for the kitbash generator, not a template. It compiles, runs and
passes its own tests on its own, and it is the source `recipes/build-maven` was extracted
from, and the second half of §28's proof that `addDependency` is format-aware rather than
Gradle-shaped (§4, §28).

`README.md` is deliberately *not* about any of that: it is the README a generated project gets, so
it has to describe only the stack that was selected. Anything that is true of this directory because
it is a reference project — including this file — lives here instead, and is listed under
`excludeFromExtraction` in [`reference-variables.json`](reference-variables.json).

The full workflow, and the justification for every path the equality test ignores, are in
[`docs/reference-projects.md`](../../docs/reference-projects.md).

## The contract

A CI test renders the recipes with the selection in `reference-variables.json` and asserts the
result equals this directory **byte for byte**. Without it these projects quietly become
documentation that lies: somebody fixes a bug here, nobody ports it into the recipe, and the emitted
project keeps the bug.

It is also what makes recipe maintenance bearable. The workflow is:

1. Edit this project like any other project. Run it, test it, fix it.
2. Run `./gradlew :verify:test` from `server/`.
3. The failure names the file and the first differing line. Port that change into the recipe.
4. Re-run until green.

The direction matters: **the reference is the thing being maintained and the recipe follows it.**
The one exception was the extraction commit itself (`kitbash-28` for this project), where
composition legitimately reordered things a human had written by hand — `.gitignore` sections land
in apply order, a patched pom comes back through its serialiser — and the generated tree was
adopted once, with that diff reviewed in the commit.

## Why this project exists beside the Gradle one

The sources under `src/` are identical to the Gradle reference's, deliberately. §28 adds Maven to
test one thing — whether `addDependency` is a format-aware operation or a Gradle-shaped one
wearing a general name — and a second reference that also changed the application would be testing
two things at once and answering neither.

## The marker comments

`<!-- kitbash:… -->`, `<!-- kitbash:dependencies -->` and `# kitbash:versions` are anchors, not
placeholders.
They are where one recipe inserts into a file another recipe owns, and they ship in generated
projects on purpose: a maintainer who later adds a dependency wants to know where the generated
block ends and theirs begins.

## Renaming things

The names that become generator variables are listed in `reference-variables.json`. Renaming the
package, the group or the example entity means editing that file in the same commit, or the
equality test starts comparing the wrong things.
