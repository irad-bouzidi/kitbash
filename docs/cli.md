# The `kitbash` CLI

Offline generation: no server, no database, no Spring context.

## Install

Download the archive for your platform from the [releases page][releases] and unpack it anywhere:

```bash
tar -xzf kitbash-<version>.tgz
./kitbash/bin/kitbash --version
```

**No JDK required.** The distribution carries its own Java runtime, its own recipes, and a launcher
that finds both — which is what makes generation work offline. Nothing needs to be on `PATH` and
nothing is installed system-wide; put `kitbash/bin` on your `PATH` if you want the short form.

[releases]: https://github.com/irad-bouzidi/kitbash/releases

### What `--version` tells you, and why there are two numbers

```
kitbash 0.3.1
catalog sha256:096dae1b50793d6d0523d9ac0dd37403d8656fa23f52147980172516291a16c5
  from /opt/kitbash/recipes
```

A CLI **carries its catalog**, so which catalog it carries is part of its identity. Two installs of
the same version built from different commits generate different projects, and the digest is the
only thing that distinguishes them — it is the first thing to quote in a bug report, for the same
reason the API exposes it.

It follows that a stale binary emits a stale catalog. That is why the digest is in `--version`, in
the generated project's README, and in every error the CLI prints: visible rather than surprising.

The digest is computed from the recipes this run would actually use, including whatever `--catalog`
points at — not recorded at build time. The two differ exactly when somebody needs to know.

### From source

```bash
cd server && ./gradlew :cli:installDist
./server/cli/build/install/kitbash/bin/kitbash --help
```

This build has **no** embedded catalog, which is deliberate: inside the repository the upward search
finds the working tree's `recipes/`, so a developer running the CLI here generates from the catalog
under review rather than one baked into a binary.

### Platforms and the glibc floor

The runtime is produced by `jlink`, which links against the glibc of the machine that built it — so
the release job builds inside a **Debian 12** container rather than on whatever the runner happens
to be. A distribution built on a newer host refuses to start on an older one with
`GLIBC_2.38 not found`, which is not something a published binary should teach its users.

`jlink` rather than a native image, because what the smoke test checks is "runs with no JDK", and
`jlink` gets there with the JDK the build already has: no second toolchain, no reflection
configuration for Jackson and Pebble, no per-platform native build. The cost is size — tens of
megabytes rather than one — and startup time, which nothing here is measuring.

## Commands

```bash
kitbash generate --selection selection.json --out ./target [--zip] [--catalog <dir>]
kitbash validate --selection selection.json [--catalog <dir>]
kitbash catalog  --json [--catalog <dir>]
kitbash --version
```

| Exit | Meaning |
| --- | --- |
| `0` | Success |
| `1` | The selection was refused. stderr carries the §14 error envelope as **JSON** |
| `2` | The command line was wrong. stderr carries the usage text |

The envelope on stderr is parseable on purpose — CI is the caller that matters, and a matrix
cell that fails should report the code, the stage, the recipe and the file in a form a job can
read rather than a sentence somebody has to grep:

```json
{
  "error" : "UNKNOWN_RECIPE",
  "stage" : "parse",
  "recipe" : "backend-spring-jva",
  "message" : "No recipe with id 'backend-spring-jva' exists in this catalog.",
  "hint" : "Did you mean one of: backend-spring-java, base, build-gradle-kts?"
}
```

Nothing else is written to stderr. A no-op SLF4J binding is on the runtime classpath for exactly
that reason: without it a transitive dependency prints three warnings there, and the envelope
stops parsing.

## `--catalog`

Points at a recipe tree other than the repository's, which is what makes local recipe development
pleasant: edit a manifest, run `kitbash generate --catalog ./my-recipes`, look at the output. With
no flag, the nearest `recipes` directory at or above the working directory is used — the same rule
the API follows, deliberately, because two ways of finding the catalog would eventually find two
different ones.

## Why it exists

§12 has every verification cell call the generator through here rather than over HTTP. That keeps
verification independent of the API, its auth and its persistence, so a red cell means the
generator is broken rather than the deployment — and it is what
[`verification/generate.sh`](../verification/generate.sh) does.

It is also the cheapest possible check that the §6 module boundary is real. `cli` depends on
`core`, `catalog` and `render` and nothing else; a Spring coordinate reaching its runtime classpath
fails the build through `checkModulePurity`. If the CLI can generate a project, the domain really
is framework-free.

## Drift

`kitbash catalog --json` and `GET /api/v1/metadata` return the same document, and a test asserts
they are equal node for node. Both call the same assembler, so what the test really guards is that
neither side has grown a second path — the drift it prevents would first show up as a CLI that
describes options the wizard does not offer.

Argument parsing is deliberately thin. Everything here is a shell around the same pipeline
functions the API calls; logic that appears in the CLI rather than in `core` will eventually differ
between the two surfaces, and the one place that would show is a user's download.
