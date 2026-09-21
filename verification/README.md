# `/verification` — proving the output builds

A generated project is verified by building it, in a container, on a machine that is not the
API host (§12, §13). This directory holds the cell runner and one image per ecosystem.

One runner, three triggers. §12 is explicit that the nightly matrix and per-generation build
validation are the same machine with different inputs, so it is built once: the merge-request
job and the nightly job differ only in which cells they select, and
[`kitbash-37`](../docs/tasks/phase-4-validation-polish/kitbash-37-on-demand-verify-job.md) adds
the third trigger by handing the same runner a selection that never came from a file.
[`kitbash-35`](../docs/tasks/phase-3-catalog-breadth/kitbash-35-full-matrix-sharding.md) scales
it to the full matrix with sharding.

## Run the matrix locally

```bash
docker build -t kitbash/verify-jvm:latest  -f verification/images/jvm/Dockerfile  .
docker build -t kitbash/verify-node:latest -f verification/images/node/Dockerfile .

cd server
./gradlew :verify:runMatrix                            # the merge-request cells
./gradlew :verify:runMatrix -Pkitbash.trigger=nightly
```

Or one cell, the way a failure tells you to:

```bash
./verification/run-cell.sh full-stack
```

That script is a thin wrapper over the same runner — one cell instead of a trigger's worth. It
owned its own `docker run` until the second ecosystem arrived and it would have built the
frontend-only project with `./gradlew` in the JDK image; a reproduction command that runs
something other than what CI ran is worse than none.

A failing cell prints the exact selection it generated from and that command, so reproducing a
red pipeline is copy and paste rather than archaeology. Every cell's log opens with both, so the
artifact somebody downloads three days later is still self-contained.

## Cells

A cell is **data**: a selection, the triggers it runs on, and one step per ecosystem. A
full-stack project has two builds, so it has two steps — merging them into one would mean one
image carrying both toolchains, and an ecosystem image that accumulates tools stops resembling
a user's machine.

```json
{
  "id": "full-stack",
  "selection": "selections/phase1-full-stack.json",
  "triggers": ["merge-request", "nightly"],
  "steps": [
    { "ecosystem": "jvm",  "workingDirectory": ".",        "commands": ["./gradlew build --no-daemon"] },
    { "ecosystem": "node", "workingDirectory": "frontend", "commands": ["pnpm install --frozen-lockfile", "pnpm test"] }
  ]
}
```

Data rather than code so that `kitbash-35` can enumerate ninety cells without generating ninety
lines of Java, and so that adding one is a reviewable diff a maintainer can write without
touching the runner.

**A step is one container**, not one per command. The entrypoint copies the read-only project
into a fresh workspace, so a container per command threw `node_modules` away between
`pnpm install` and `pnpm lint` — the frontend cell failed with `eslint: not found` on a tree it
had just installed into. The commands of one build share a filesystem; the script stops at the
first failure and names it, so a four-command step still reports which of the four broke.

Four of the eleven cells today are the four §17 asks for: backend only, frontend only, both, and
both with containers declined. The frontend-only case is the one most likely to break silently,
which is why it is a cell rather than an assumption.

The other seven cover the three axes the catalog has grown — build tool (§28), language (§29) and
architecture (§30) — **pairwise**. The full cross-product is 2 x 2 x 3 = twelve; these eight
contain every pair of values drawn from any two axes, which is where the defects that matter live.
A fragment that is correct for Kotlin-on-Gradle and for Java-on-Maven and wrong for their
combination is caught. A three-way interaction with no two-way symptom is not, and that is the
trade being made on purpose.

`CellTest.coversEveryPair` asserts it rather than trusting the list: deleting a cell to make the
matrix faster fails with the name of the pair that stopped being built.

## What is here

| Path | What it is |
| --- | --- |
| `cells/*.json` | One file per cell: a selection, its triggers, and one step per ecosystem. |
| `selections/*.json` | The §7 envelopes the cells generate from. |
| `images/jvm/**` | JDK 21, warm Gradle, Kotlin and Maven caches, `unzip`, `git`. Nothing else. |
| `images/node/**` | Node 24, pnpm with a warm store, `unzip`, `git`. Nothing else. |
| `generate.sh` | Selection in, zip out. **The one replaceable step** — see below. |
| `run-cell.sh` | `run-cell.sh <cell-id>` — one cell, for reproducing a failure. |
| `build/` | Output: `status.html`, `status.json` and a log per cell. Not checked in. |

The runner itself is `server/verify`, in Java, because `kitbash-37` has to construct a cell from
a user's selection and serve its logs back — which wants a result model rather than a shell
script's exit code.

## The one replaceable step

`generate.sh` has a fixed contract — a selection file in, a zip out. Phase 0 implemented it by
booting the server and calling `POST /api/v1/generate`; `kitbash-17` replaced the body with a
CLI invocation and nothing else about the job changed, which is exactly what putting it behind
a script instead of inlining curl into two CI files was for.

It calls the CLI rather than the API on purpose (§12): verification stays independent of the
API, its auth and its persistence, so a red cell means the generator is broken rather than the
deployment.

## The images are warmed, on purpose and precisely

Both images build the corresponding reference projects at image-build time and keep the caches.
That is a speed optimisation, not a correctness one: builds still resolve against the network, so
a dependency that disappears upstream still breaks the cell. An offline cache would silently go
stale and then hide real breakage.

What is warmed matters as much as that it is. The JVM image warms with
`build -x test compileTestJava` rather than `build -x test`: the latter never resolves the *test*
classpath, so every cell re-downloaded JUnit, AssertJ and Testcontainers before it could compile
a test — minutes per cell, straight off the ten-minute merge-request budget. Compiling the tests
warms exactly those coordinates and still needs no Docker daemon at image-build time, which
running them would.

Maven arrived with §28 and needed the same treatment plus one more variable. `MAVEN_USER_HOME` is
what the wrapper script reads to decide where to unpack the Maven distribution; `maven.repo.local`
is what Maven itself reads to decide where the artefacts go. Warming with only one of them set
leaves the cell re-downloading the other half — which is how `backend-maven` came to take 72s
against `backend-only`'s 22s before the image warmed it. With both set and `test-compile` run at
image-build time, it takes 25s.

Kotlin arrived with §29 and needed no new mechanism, only another warm-up: the compiler, the
`spring`/`jpa` compiler plugins and the standard library are another sixty-odd megabytes that
`backend-kotlin` would otherwise fetch before compiling a line. It warms from the Kotlin reference
project, which is a real project like the other two.

One cell is deliberately left cold: `backend-kotlin-maven` takes 63s against the others' 20–37s,
because the Kotlin *Maven* plugin lives in the Maven repository and nothing in the image puts it
there. Warming it would mean a Kotlin Maven project inside `images/jvm/`, and that is a fourth
project to keep in step with the recipes in exchange for forty seconds of a 600s budget. Worth
revisiting when the budget is tight; not before.

The Node image has the same shape of trap. Corepack downloads the pnpm version a project pins in
`packageManager` and caches it under `$COREPACK_HOME`, which defaults to the *current user's*
home — so warming as root and running the cell as `builder` left the cache unreadable and every
cell re-downloaded pnpm before it could install anything. `COREPACK_HOME` is a shared directory
handed to `builder`, and `COREPACK_ENABLE_DOWNLOAD_PROMPT=0` because a cell has no terminal to
answer a prompt on.

## Isolation

Per §13, and from the first cell rather than retrofitted:

| Control | Setting | Why |
| --- | --- | --- |
| Not the API host | the build runs inside the ecosystem container | The rule that holds from the start: a generated build never runs where the API runs. |
| Filesystem | project mounted read-only at `/input`, copied to `/workspace` inside | A generated build cannot write to the runner's filesystem, and the container's uid does not have to match the host's. |
| CPU | `--cpus=2` (`KITBASH_CELL_CPUS`) | A cell that can eat the runner takes the whole matrix with it. |
| Memory | `--memory=4g`, swap capped to the same | As above, and it surfaces a generated project that is accidentally enormous. |
| Processes | `--pids-limit=2048` | A fork bomb in a build script is a plausible accident. |
| Privileges | `--security-opt=no-new-privileges`, non-root user | Standard, free. |
| Timeout | 900 s hard kill (`KITBASH_CELL_TIMEOUT`) | A hung build is a failed build. |
| Budget | 600 s for the whole run (`KITBASH_MATRIX_BUDGET`) | §12's ten minutes. Printed every run, not only when breached, so the trend is visible before it is a problem — it does not turn the matrix red, because a red cell should mean the generated project is broken rather than that the runner had a slow afternoon. |

### Two gaps, named rather than hidden

**Network.** §12 asks for no network beyond a dependency proxy or mirror. There is no proxy
yet, so a cell resolves against the real network from a dedicated bridge network. The image's
warm Gradle cache means the common case downloads almost nothing, but a cell can still reach
the internet. Closing this needs a proxy service in the job, and it belongs with `kitbash-35`
where the cost of *not* having one multiplies by the number of cells.

Note the cache is warm on purpose and **not** offline: a fully offline cache silently goes
stale and then hides real breakage. A dependency that disappears upstream should break the
cell.

**The Docker socket.** The generated project's own test suite uses Testcontainers, so the cell
needs a Docker daemon; it gets the host's, through a mounted socket, and reaches the
containers it starts through the host gateway. The alternative is skipping the generated
project's integration tests, and a cell that does not run them is not verifying very much.
A sibling daemon (`dind`) removes the sharing and costs startup time per cell — worth
revisiting at matrix scale, not at one cell.

## Adding a cell

Add a selection to `selections/` and a cell to `cells/`. That is the whole process: no CI file
changes, because the triggers select by tag.
