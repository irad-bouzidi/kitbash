# The recipe manifest format

A recipe is a directory under [`/recipes`](../recipes): one `recipe.yaml` plus the files and
patch operations it contributes. Recipes are the only place in this repository allowed to know a
technology's name (§19), and this document is the contract between a recipe author and the loader.

The machine-readable version is [`/recipes/_schema/recipe.schema.json`](../recipes/_schema/recipe.schema.json).
It is the same file the loader validates against and the one to point an editor at:

```yaml
# yaml-language-server: $schema=../_schema/recipe.schema.json
```

Everything below is enforced. A manifest that breaks any of it fails application startup with a
message naming the file, the field and the fix — the loader is the only gate between a malformed
recipe and a user's broken download (§10), so it fails loudly and early rather than at render time.

---

## A worked example

```yaml
# yaml-language-server: $schema=../_schema/recipe.schema.json
id: backend-spring-java          # also the directory name; the two must agree
version: 1.4.0                   # the recipe's own semver
frameworkVersion: "3.5.5"        # what it emits, surfaced in the UI
kind: backend                    # base | backend | frontend | mobile | feature | infra | ci
label: Spring Boot (Java)
slot: backend                    # the option that selects it

provides: [http-server, rest-api, openapi-spec, jvm-project, java-sources]
requires: [build-tool, database]
# No `conflictsWith: [backend-spring-kotlin]`, although the two really are exclusive: they share
# the `backend` slot, a slot holds one recipe, and the loader refuses a conflict that only says
# that again. See "a slot holds one recipe" below.

hook: true                       # a hook is registered for this id in core (see docs/hooks.md)

options:
  - id: architecture
    type: enum
    values: [layered, hexagonal, modular-monolith]
    default: layered
    label: Architecture
    help: Determines the package layout and dependency direction.

variables:
  required: [groupId, artifactId, packageName, javaVersion]

files:
  - from: files/**
  - from: arch/hexagonal/**
    when: architecture == 'hexagonal'

patches:
  - op: appendLines
    target: .gitignore
    lines: ["build/", ".gradle/"]
  - op: addComposeService
    target: compose.yaml
    service: api
    when: capability('containers')
    definition:
      build: .
      ports: ["8080:8080"]
```

---

## Fields

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Lowercase kebab-case, and **equal to the directory name**. A mismatch is an error, because a recipe that is findable under two names is a recipe somebody will pin under the wrong one. |
| `version` | yes | The recipe's own semver, `MAJOR.MINOR.PATCH`, no pre-release or build metadata. This is what a pin references (`backend-spring-java@1.4.0`) and what a generation lock records (§7). |
| `frameworkVersion` | no | What the recipe emits — Spring Boot 3.5.5, React 19. Display text, bumped by the weekly freshness job (§12). Never compared or ordered. |
| `kind` | yes | The slot the recipe fills, which is **also its position in the apply order**: `base` → `backend` → `frontend` → `mobile` → `feature` → `infra` → `ci`, ties broken by recipe id (§4). |
| `label` | yes | What the wizard shows. Every human-readable string comes from the server (§8). |
| `provides` | no | Capabilities this recipe satisfies for others. |
| `requires` | no | Capabilities it needs. Every one must be provided by *some* recipe in the catalog, or the tree does not load. |
| `conflictsWith` | no | Recipe ids that cannot be selected alongside this one. Each must exist. |
| `hook` | no | Declares that a hook is registered for this recipe **in `core`**. A recipe directory can say it has a hook; it cannot supply code (§4, §13). |
| `options[]` | no | The knobs this recipe exposes. |
| `variables.required[]` | no | Template variables the recipe reads and the request must supply. |
| `files[]` | no | Globs of files it contributes, with an optional condition. |
| `patches[]` | no | Typed edits to files *other* recipes produced. |

### Capabilities, not recipe names

`requires` and `provides` are how recipes coordinate. **No recipe may name another recipe**, except
in `conflictsWith`. The React recipe says `requires: [rest-api]`; it neither knows nor cares which
backend satisfies that. This is what lets the resolver validate a selection structurally instead of
against a hand-written compatibility matrix (§4) — and the matrix is what the alternative design
degenerates into within a quarter.

### Options

```yaml
options:
  - id: architecture     # camelCase, unique within the recipe
    type: enum           # enum | boolean | string | multi-select
    values: [layered, hexagonal, modular-monolith]
    default: layered     # must be a value this option can hold
    label: Architecture
    help: Determines the package layout and dependency direction.   # required
```

`type` is a closed set because the wizard's only switch is on option *type*, never on option id
(§9). `help` is required: an option nobody documented is an option somebody explains once in a wiki
page nobody reads. An `enum` with no declared `default` takes its first `values` entry, so the order
of that list is meaningful and worth reviewing.

**Two recipes in the same slot may declare the same option**, and both JVM backends declare
`architecture`. They get *one* control in the wizard whose `availableWhen` lists both of them —
because two controls would share an id, and one of them would always be the disabled duplicate.
The price is that the two declarations have to be identical: type, values, default, label and help.
A disagreement fails when the catalog loads, naming the slot and the option, rather than showing a
user whichever of the two the assembler happened to reach first.

---

## The catalog manifest

A recipe describes itself. What the catalog *offers* — the wizard's sections, the slots a recipe
can fill, the free-text inputs — is described once, in
[`/recipes/_catalog.yaml`](../recipes/_catalog.yaml), validated against
[`_schema/catalog.schema.json`](../recipes/_schema/catalog.schema.json).

```yaml
schemaVersion: 1

variables:
  - id: packageName
    label: Package name
    help: Root package of the generated sources.
    pattern: "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$"
    default: com.example.demo

groups:
  - id: stack
    label: Stack
    order: 1
    slots:
      - id: backend
        type: enum          # enum | boolean
        label: Backend
        help: The service that owns the data and serves the API.
      - id: docker
        type: boolean
        label: Containers
        help: A compose file wiring the stack together.
        defaultOn: true
```

Each recipe then names the slot it fills:

```yaml
slot: backend
```

**Why this is a separate file.** The resolver could work without it — an option whose value names a
recipe id selects that recipe, and that is enough to resolve a selection somebody has already made.
It is not enough to *render* one: a wizard needs the slot's id, label, help, type and position
before any value exists, and no amount of looking at recipes will produce them. §8 asks for exactly
this ("option groups carry display order and labels"), and declaring it removes the last piece of
guesswork from the engine.

A recipe with **no** `slot` can only be reached by implication. `base` is the case: nothing offers
it as a choice, and it arrives because something else requires `project-root`.

Two rules the loader enforces in both directions, because both failures are silent at runtime and
obvious at boot:

- a recipe naming a slot nobody declared is unreachable — no option would ever select it;
- a slot nobody fills renders as an empty dropdown, which looks like a bug in the wizard.

And one consequence worth knowing: **a slot holds one recipe, so two recipes in the same slot
already exclude each other.** A `conflictsWith` between them is dead configuration that looks like
protection, and the loader refuses it. `conflictsWith` is for recipes in *different* slots, where a
selection really can name both.

---

## How a recipe gets selected

The selection envelope (§7) is flat: option id → value. Three rules turn that into a set of
recipes, and there is nothing else:

1. **An enum slot's value names a recipe assigned to that slot**, and selects it. `"backend":
   "backend-spring-java"` selects it; a value naming nothing in that slot selects nothing.
2. **A boolean slot set to `true` selects the recipes assigned to it.** This is how
   `"docker": true` reaches the container recipe without the envelope, the wizard or the resolver
   ever naming `infra-docker`.
3. **A recipe's own boolean option that declares `demands`** adds that capability as a requirement
   when it is on. §18 needs this: a standalone frontend is supported, so `frontend-react-vite`
   cannot require a REST API — while its typed-client option genuinely does need a backend that
   publishes an OpenAPI document.
4. **Everything else is configuration**, read by templates and by `when` expressions.

The resolver then expands what the selection implies: a `requires` capability with exactly one
provider in the catalog is selected automatically, and one with several comes back as a *choice*
rather than a guess — picking for the user is how somebody ends up with jOOQ because it sorted
before JPA. Implied recipes are reported separately so the wizard's right rail can show what was
added rather than leaving it to be discovered in the zip (§9).

### `requires` is a selection constraint, not an ordering one

Worth stating plainly, because reading it the other way produces a cycle in the real catalog:
`backend-spring-java` requires `database`, and `db-postgres-flyway` requires `jvm-project`, which
the backend provides. As ordering edges those two are a loop. They are not one: `requires` says
*this capability must be present in the selection*, while what comes first is fixed by `kind`.
The database recipe patches a project the backend has already laid down.

So the apply order is kind order, ties broken by recipe id, and the dependency graph is consulted
only *within* a kind — where kind gives no order and one feature genuinely can build on another. A
cycle can therefore only be two recipes of the same kind each requiring what the other provides,
which is a manifest bug worth reporting by name.

---

## The `when` expression language

`when` is the entire conditional surface of the format, and it is deliberately tiny (§7). The
grammar, in full:

```
expression := term (('&&' | '||') term)*     # one operator per expression, never both
term       := always
            | option                          # true when the option reads as true
            | !option
            | option == 'literal'
            | option != 'literal'
            | capability('name')
```

| Form | True when |
| --- | --- |
| `always` | Always. This is also what an omitted `when` means. |
| `docker` | The option reads as true: a boolean that is `true`, a string that is neither empty nor `false`, a multi-select that is non-empty. |
| `!docker` | The negation of the above. |
| `architecture == 'hexagonal'` | The option's scalar value equals the literal. A boolean compares against `'true'`/`'false'`; a multi-select never matches. |
| `architecture != 'layered'` | The negation. |
| `capability('rest-api')` | Some selected recipe provides that capability. |

There are no parentheses, no arithmetic, no method calls and no other functions. Mixing `&&` and
`||` in one expression is **rejected** rather than given a precedence rule nobody would remember;
split it into two rules instead.

Two things the loader checks and neither the schema nor the renderer could:

- every option a `when` reads must be declared by the same recipe under `options[]`;
- every capability a `when` tests must be provided by *some* recipe in the catalog, since a
  condition that can never be true is a file set somebody meant to ship and silently is not.

`capability(…)` exists for the standalone-frontend case (§18): a recipe has to be able to say "wire
this up only when something provides a REST API" without naming the recipe that does.

---

## Files

```yaml
files:
  - from: files/**
  - from: arch/hexagonal/**
    when: architecture == 'hexagonal'
```

`from` is a glob relative to the recipe directory, matched with Java's `glob:` syntax (`**` crosses
directories). **A glob that matches nothing is an error** — a rule matching nothing is a file set
somebody meant to ship and silently is not shipping, and that failure is otherwise invisible until
a user's project will not compile.

---

## Templating

Paths inside the tree are themselves templated, so
`files/src/main/java/{{ packageName | packagePath }}/Application.java.peb` lands where javac
expects it. The `.peb` suffix marks a file as a template and is **stripped from the output**; a file
without it is copied through byte for byte.

That distinction is load-bearing rather than tidy: a Gradle Kotlin DSL script, a shell script or a
GitHub Actions workflow can contain `{{ }}` for its own reasons, and a binary run through a text
engine comes out corrupted. Only name a file `.peb` when it is genuinely a template. A binary that
is named `.peb` anyway is refused with `RENDER_FAILED` rather than mangled.

### Filters

Five, and no others:

| Filter | `com.acme.customer` / `customer-management` becomes |
| --- | --- |
| `packagePath` | `com/acme/customer` |
| `camel` | `customerManagement` |
| `pascal` | `CustomerManagement` |
| `kebab` | `customer-management` |
| `snake` | `customer_management` |

They are total: any of the five naming forms converts to any other, so a recipe never has to know
whether the caller typed `customer-management` or `CustomerManagement`. Pebble's own standard
library is aimed at rendering HTML for humans and is not registered. A recipe that needs something
these cannot express is usually a recipe that should be computing it in a hook
([`docs/hooks.md`](hooks.md)) instead — and any filter added here must be documented in this table
in the same change, because an undocumented filter is one only its author can use.

### What a template cannot do

A generator writes strings into files somebody is then going to execute, so the engine is fenced in
(§13):

- **No method access at all.** `{{ ''.getClass() }}`, `{{ name.toUpperCase() }}` and every other
  route from a value into the JVM fail. Pebble ships a blacklist validator; a blacklist is a list of
  the attacks somebody already thought of, and there is nothing in the variable map worth calling a
  method on.
- **No filesystem.** Templates resolve against the catalog's own set and nothing else, so
  `{% include "/etc/passwd" %}` fails because that name does not exist — not because a path check
  caught it. An `include` of a template the catalog *does* contain works, which is the point.
- **No lenient variables.** A typo is `RENDER_FAILED` naming the template and the line, not a
  silently empty package declaration the user discovers at their first compile.
- **Primitives only.** The variable map holds strings, booleans, numbers and lists of strings. It is
  not possible to pass a live object into a template.
- **No HTML escaping.** The output is source code; escaping it would be corruption, not safety.

---

## Patches

Several recipes need to modify the same file. Free-text appending produces broken syntax within a
week (§4), so every edit is typed and format-aware, and every patch names both the recipe that owns
it and the file it targets. **The set of eight operations is closed**: a recipe that seems to need a
ninth is a design discussion, not a manifest change.

| `op` | Required keys | Targets |
| --- | --- | --- |
| `addDependency` | `configuration`, `coordinate`, optional `versionRef` | a **module** — `.` for the root, `frontend` for a subdirectory |
| `mergeYaml` | `content` | `application.yml`, `compose.yaml`, CI files |
| `mergeJson` | `content` | `package.json`, `tsconfig.json` |
| `addScript` | `name`, `command` | `package.json` |
| `insertAtMarker` | `marker`, `lines` | any text file |
| `appendLines` | `lines` | `.gitignore`, `.env.example` |
| `addEnvVar` | `name`, `value`, optional `comment`, `composeTarget`, `composeService` | `.env.example` **and** `compose.yaml` |
| `addComposeService` | `service`, `definition`, optional `dependsOn` | `compose.yaml` |

`addDependency` is the one operation whose `target` names a directory rather than a file, and §28
is why. A recipe declaring `spring-boot-starter-web` knows which *module* the dependency belongs to;
it does not know, and must not know, whether the user picked Gradle or Maven. So the target is the
module, the applier looks for `build.gradle.kts`, `build.gradle`, `pom.xml` or `package.json` inside
it, and the format strategy takes over from there — including translating Gradle's configurations to
Maven's scopes (`implementation` is Maven's default and so written as no scope at all;
`testImplementation` becomes `test`; a configuration with no Maven meaning is refused rather than
guessed at). A module containing no build file is `PATCH_TARGET_MISSING`, as it would be for a file.
Dedupe is by `group:name` in both formats: the same artifact asked for twice at different versions
is one declaration, and the first one wins, because picking the second would make the output depend
on recipe order.

`appendLines` dedupes **line by line across the whole file**, which is what a `.gitignore` wants —
two recipes both ignoring `build/` should produce one entry. It is the wrong tool for a sectioned
file: in an `.editorconfig`, `indent_size = 2` under one section is not the same statement as
`indent_size = 2` under another, and the dedupe would silently drop it. Sectioned files get a
marker instead.

Every patch is idempotent, and every one is applied in resolved recipe order so the output stays
byte-identical across runs. A patch against a file **no selected recipe produced** fails at validate
time with `PATCH_TARGET_MISSING` and a hint naming the recipe that wanted it — never silently (§4,
§14).

`insertAtMarker` targets a `// kitbash:…` marker placed by whichever recipe owns the file, and
inserts **above** it. Above rather than below, so that when several recipes share one anchor their
blocks stack in resolved recipe order instead of in reverse — which puts the marker at the foot of
the section it anchors, and that is where the owning recipe should place it.

Markers ship in generated projects on purpose. A maintainer who later adds a dependency by hand
wants to know where the generated block ends and theirs begins, and a marker is a far better answer
than a convention nobody wrote down. Place them deliberately and document each one in the owning
recipe's manifest, because another recipe will target it later.

### Conventions a capability carries

A capability is not only a name — it is a small contract, and some of them promise more than
existence:

| Capability | Also guarantees |
| --- | --- |
| `project-root` | A `README.md` carrying the five `<!-- kitbash:… -->` section markers, a `.gitignore`, a `.env.example`, and an `.editorconfig` carrying `# kitbash:sections` |
| `build-tool` | A build file with a `dependencies` block and four markers — one for plugins, one for project-level build configuration, one for formatter configuration, one for version declarations — plus, on Gradle, a version catalog carrying `# kitbash:versions`, `# kitbash:libraries` and `# kitbash:plugins` |
| `containers` | A `compose.yaml` whose application service is named `app` |
| `java-sources` / `kotlin-sources` | A `src/main/<language>` and `src/test/<language>` tree, for a recipe that has to ship a source file of its own |

A build tool knows how to build a JVM project; it does not know which language the project is
written in. That is why `build-tool` promises markers rather than content: the recipe that brings
the sources contributes the formatter configuration and the compile settings, and a Kotlin project
does not carry a Java formatter for files it does not have. §29 is where that stopped being theory —
`build-gradle-kts` had quietly named `palantir-java-format` and configured `JavaCompile`, and the
second language is what made it visible.

`java-sources` and `kotlin-sources` exist for the same reason and at a finer grain than
`jvm-project`: two backends provide `jvm-project`, so a recipe that must emit an integration test
cannot use it to choose a language. The database recipe carries the same test twice, one tree per
language, selected by a `when` on these.

That last one is what lets the database recipe wire `depends_on` without naming the container
recipe. Recipes still never name each other (§4); they agree on a contract that the capability
stands for.

---

## The catalog digest

The loader computes one digest over the whole tree (§7). It is the zip cache key's other half
(§10), the verification dedupe key (§12), part of every generation lock, and what the web UI shows
in its footer — which is what makes a bug report actionable (§9).

It is `sha256` over the sorted set of `(recipeId, version, contentHash)`, where `contentHash` is
`sha256` over **every file in the recipe directory**: relative path first, then bytes, sorted by
path. Nothing is excluded.

Two consequences worth stating plainly:

- Editing a template body moves the digest. It has to: otherwise a zip cached from the old body
  stays servable.
- Renaming a template moves the digest even when its content is unchanged, because the path is
  hashed too.

The digest is stable across machines: paths are relativised and normalised to `/`, bytes are hashed
rather than strings, and the order is fixed by an explicit comparator.

---

## What the catalog is not

Recipes live in git and are validated at boot (§10). There is no `technology` table, no
`architecture` table and no admin CRUD screen; a database-backed catalog is only needed for
user-uploaded recipes, which is a phase 5 question carrying its own threat model (§13,
[`kitbash-47`](tasks/phase-5-extension/kitbash-47-user-contributed-recipes.md)). A test asserts that
no migration ever describes recipes, technologies or architectures.

Recipe content is extracted from a real compiling project under [`/reference`](../reference), never
written from scratch, and a CI test binds the two together (§4,
[`kitbash-19`](tasks/phase-1-recipe-engine/kitbash-19-reference-equality-test.md)). If you are
editing template soup here to fix a generated project, fix the reference project and re-extract.
