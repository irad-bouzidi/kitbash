# The `kitbash` CLI

Offline generation: no server, no database, no Spring context.

```bash
cd server && ./gradlew :cli:installDist
./server/cli/build/install/kitbash/bin/kitbash --help
```

## Commands

```bash
kitbash generate --selection selection.json --out ./target [--zip] [--catalog <dir>]
kitbash validate --selection selection.json [--catalog <dir>]
kitbash catalog  --json [--catalog <dir>]
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
