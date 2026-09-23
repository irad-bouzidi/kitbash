# Writing a recipe

A recipe is a directory under [`/recipes`](../recipes) that contributes files and patch operations
to a generated project. This document is the **workflow**;
[`recipe-format.md`](recipe-format.md) is the field-by-field contract, and
[`hooks.md`](hooks.md) is the escape hatch you probably do not need.

The one rule worth reading first, because everything else follows from it:

> **A recipe is extracted from a project somebody maintains by hand.** It is not written from a
> blank manifest.

§4 put it that way for a reason. Templates cannot be proved by inspection — a generator whose
output merely looks right is a generator that emits projects nobody can compile. So the source of
truth is a **reference project** under [`/reference`](../reference): a real project, built by a
real build, that a person edits. Recipes reproduce it, and a test says whether they still do.

The loop is therefore:

1. change the reference project by hand, and prove it builds
2. port that change into a recipe
3. run the check until generating reproduces the reference again

Never the other way round. Writing the template first means the first thing that proves it is a
user's download.

---

## The harness

```bash
kitbash recipe check <recipe-id>
```

Under a second, no server, no database, no containers. It gives **the same verdicts CI gives** —
the same loader, resolver, renderer and patch applier — because a tool that passes locally and
fails in CI teaches people to distrust the tools.

| Check | What a failure means |
| --- | --- |
| `manifest` | The manifest does not match the schema, or a `from:` glob matches nothing. |
| `renders` | Generation was refused. `PATCH_COLLISION` means two recipes claim one key; `PATCH_TARGET_MISSING` means a patch names a file no selected recipe produces. |
| `deterministic` | Two generations of one selection differed. §4 requires byte equality, and the zip cache rests on it. |
| `reference` | Generating no longer reproduces the reference project. Port the diff. |

What it **cannot** tell you is whether the generated project builds. That needs a container and
minutes, and it is what a matrix cell is for — the last line of every run says so rather than
letting green imply more than it proved.

A recipe nothing exercises reports `--  not wired` rather than a failure, and exits non-zero: a
recipe no reference project selects has been proved by nothing.

---

## A worked example, start to finish

Every command and every output below was executed. The recipe it produces is
[`feature-codeowners`](../recipes/feature-codeowners), and
[`verification/cells/authoring-example.json`](../verification/cells/authoring-example.json) builds
it on every merge request — a walkthrough whose result is never built is a walkthrough that rots.

### 1. Scaffold

```bash
kitbash recipe new feature-codeowners --from spring-boot-java-gradle-layered
```

```
Created recipes/feature-codeowners/
  recipe.yaml   validates now: `kitbash recipe check feature-codeowners` will say so
  files/        one placeholder, so the manifest validates. Replace it with a file
                taken from the reference project — that is the direction §4 requires
```

The scaffold wires nothing in. That is deliberate: the four edits below are the model, and an
author who has made them once understands it while one who had them made for them does not.

### 2. Declare the slot

A **slot** is how a choice reaches a user. The wizard renders one control per slot and knows
nothing else about your recipe (§8) — so a recipe in no slot is never selected, and a slot the
catalog has not declared is refused at load.

In [`recipes/_catalog.yaml`](../recipes/_catalog.yaml), in the group it belongs to:

```yaml
      - id: codeowners
        type: boolean
        label: CODEOWNERS
        help: A CODEOWNERS file, so reviews land on the team that owns the code.
        defaultOn: false
```

Which group is an editorial decision about how the wizard reads. That is why the scaffold does not
make it for you.

### 3. Name it in the recipe

```yaml
slot: codeowners
```

### 4. Turn it on in the reference project

In `reference/spring-boot-java-gradle-layered/reference-variables.json`, add the id to
`recipeIds` — which documents what this reference exercises — and turn the slot on under
`options`, which is what actually selects it:

```json
"recipeIds": [ …, "feature-codeowners" ],
"selection": { "options": { "codeowners": true, … } }
```

### 5. Write the file by hand, in the reference project

This is the step that does the real work, and the one it is tempting to skip:

```
reference/spring-boot-java-gradle-layered/CODEOWNERS
```

Prove the project still builds. Only then copy it into `recipes/feature-codeowners/files/`, where
its path beneath `files/` decides where it lands — there is no `to:`.

### 6. Check

```bash
kitbash recipe check feature-codeowners
```

```
manifest       ok
  validates against recipes/_schema/recipe.schema.json
renders (spring-boot-java-gradle-layered) ok
  106 files, 169638 bytes, no patch collided and every patch target existed
deterministic (spring-boot-java-gradle-layered) ok
  two generations produced identical bytes
reference (spring-boot-java-gradle-layered) ok
  generating with the checked-in variables reproduces the reference project, 38 files

All checks passed. This does not mean the generated project builds — that is a
matrix cell, and it needs a container.
```

### 7. Add a matrix cell

The check's last line is the reason. A cell is a selection plus the real build commands, and it is
the only thing that proves the output compiles:

```bash
./verification/run-cell.sh authoring-example
```

```
PASSED   authoring-example          30s
```

That is the whole loop: empty directory to a project that builds, in seven steps.

---

## Beyond one file

### Capabilities, not recipe names

Recipes never name each other. A recipe declares what it `provides` and what it `requires`, and
the resolver does the rest — which is what lets a recipe added tomorrow satisfy a requirement
written last year. `requires: [http-server]` says "something with a filter chain"; naming
`backend-spring-java` would say "this one", and the Kotlin backend would then need a second recipe
that is identical apart from a string.

Pick the **weakest** capability that is actually needed. `feature-auth-jwt` requires `http-server`
rather than `rest-api` because what it protects is a filter chain.

### Cross-cutting changes are patches

A recipe that only adds files can only add files. Anything that has to change a file another
recipe owns — a dependency, a config key, a line in a `.gitignore` — is a **patch**, and patches
are how a feature reaches across a project without any recipe knowing about the others.

Auth is the worked example in the catalog: it touches seven files owned by five other recipes, and
it is one directory plus its patch list. `recipe-format.md` has the operations.

Two rules the harness enforces for you:

- **Idempotency.** Applying a patch twice produces what applying it once produced. This is not
  tidiness — recipes are applied in a deterministic but not obvious order, and an op that appends
  unconditionally produces a different file depending on what else was selected.
- **Ownership.** Two recipes cannot set the same key. The generator refuses rather than picking a
  winner, because a silent winner is a project that builds and behaves unlike what was asked for.
  If you hit `PATCH_COLLISION`, either move one behind an option so both are never selected
  together, or have the owning recipe expose a marker.

### Markers

A marker is a comment another recipe can insert at:

```
# kitbash:versions
```

It is a contract. The recipe that owns the file owns the marker; the recipes that insert at it
stack in resolution order. Put one wherever you expect a stranger to need to contribute, and
document it beside the file — an undocumented marker is a merge conflict waiting to be discovered.

Two things learned the hard way, both of which cost a debugging session:

- **A marker quoted in prose is a marker.** A Javadoc that spelled out `// kitbash:required-environment`
  had the patch match the comment.
- **Indentation is part of the contract.** A marker at a different indentation in two variants of
  a file inserts badly formatted code into one of them.

### Hooks: almost certainly not

A hook is Java that runs in the plan stage. There are three in the entire catalog and there is a
test asserting there are never more than three, because:

> If more than about three recipes need a hook, the manifest format is missing a feature.

That is policy, not advice. If you are reaching for one, the conversation to have is about the
manifest, not about your recipe. [`hooks.md`](hooks.md) explains when it is genuinely warranted.

---

## Editor support

Every manifest starts with:

```yaml
# yaml-language-server: $schema=../_schema/recipe.schema.json
```

That one line gives completion, inline validation and hover documentation in any editor with the
YAML language server — VS Code, IntelliJ, Neovim. It is the same schema the loader validates
against, so the editor and the build agree by construction rather than by maintenance.

A test asserts every recipe carries the line, because the first one written without it is the one
whose author never finds out the tooling existed.
