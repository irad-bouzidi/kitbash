# 0003 — Jackson in `core`, so patches can be format-aware

**Status** Accepted · **Date** 2026-09-20 · **Plan** §4, §5, §6, §10 · **Task** `kitbash-10`

## Context

Two instructions in the plan point in opposite directions, and `kitbash-10` is where they meet.

§6's module table says `core` depends on **stdlib only**, and `kitbash-1` enforced that with a
`checkModulePurity` task that fails the build when a framework coordinate reaches the module's
runtime classpath. §5 gives the reason: the domain must be free of Spring so the generator is
drivable from tests and from the CLI without booting a context.

The same §6 table puts the **patch appliers** in `core`. And §4 and `kitbash-10` are unambiguous
about how those appliers must work:

> Use real parsers: a YAML parser for YAML, a JSON parser for JSON … `pom.xml` gets a real XML
> tree.

> Free-text appending produces broken syntax within a week.

The JDK ships an XML parser and nothing for JSON or YAML. So the appliers cannot be both "in
`core`" and "format-aware" without either a dependency or a hand-written YAML parser.

## Decision

Add `jackson-databind` and `jackson-dataformat-yaml` to `core`, and keep the purity guard
enforcing what it was actually built to enforce: **no frameworks**. The forbidden list —
Spring, Lombok, JPA — is unchanged, and the module still has no container, no classpath
scanning, no annotation processing and no reflection over application types.

The three alternatives, and why they lose:

**Hand-write a YAML parser in `core`.** YAML is a large specification with block scalars,
anchors, flow style, multiple document forms and half a dozen ways to write a string. A subset
parser works until a recipe author writes the part that is not in the subset, and the failure
mode is a *silently corrupted* `application.yml` in somebody's downloaded project. That is the
exact outcome §4 wrote the typed patch operations to avoid; trading a dependency for it is a
bad trade.

**A codec SPI in `core` with the implementations in another module.** Architecturally tidy, and
it keeps the letter of "stdlib only". It also means the algorithm and the format handling are
tested in two places, every caller has to wire a codec, and `core` on its own can no longer
apply the operation it defines. A seam earns its keep when something is going to be swapped
behind it; nothing is going to be swapped here.

**Move the appliers out of `core`.** Contradicts §6 directly, and pushes the one component with
real merge semantics away from the module whose tests are meant to be fast and pure.

## Consequences

- `core` gains two data libraries and stays framework-free. `checkModulePurity` continues to
  gate that, and is the contract rather than the prose in §6.
- `cli` and the verification matrix still run the generator with no container, which is the
  property §5 and §12 actually depend on.
- Jackson's version is pinned in the version catalog and bumped by the weekly freshness job
  (§12), like every other coordinate.
- **Comments in patched YAML are lost.** A round trip through any tree model drops them. This
  affects only files a patch actually touches, and the recipes own those files, so the mitigation
  is to put explanatory prose in the README fragment rather than in a `compose.yaml` a feature
  recipe is going to merge into. Recorded here rather than discovered later.
- If a third format ever needs a third library, that is the signal to revisit this and extract
  the codec seam after all — the cost of the seam is worth paying once the thing behind it is
  plural.
