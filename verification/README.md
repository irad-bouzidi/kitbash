# `/verification` — proving the output builds

A generated project is verified by building it, in a container, on a machine that is not the
API host (§12, §13). This directory holds the cell runner and one image per ecosystem.

Right now there is **one cell**. [`kitbash-18`](../docs/tasks/phase-1-recipe-engine/kitbash-18-verification-runner.md)
generalises the runner over a selection set; [`kitbash-35`](../docs/tasks/phase-3-catalog-breadth/kitbash-35-full-matrix-sharding.md)
scales it to the full matrix with sharding.

## Run a cell locally

The same command CI runs, with the same image:

```bash
docker build -t kitbash/verify-jvm:latest -f verification/images/jvm/Dockerfile .
./verification/run-cell.sh verification/selections/phase0-spring-java-gradle.json
```

A failing cell prints the exact selection it generated from and that command, so reproducing
a red pipeline is copy and paste rather than archaeology.

## What is here

| Path | What it is |
| --- | --- |
| `images/jvm/Dockerfile` | The JVM ecosystem image: JDK 21, a warm Gradle cache, `unzip`, `git`. Nothing else. |
| `images/jvm/entrypoint.sh` | Copies the read-only project into a writable workspace and runs the build. |
| `generate.sh` | Selection in, zip out. **The one replaceable step** — see below. |
| `run-cell.sh` | Generate, unpack, build in the container, report. |
| `selections/*.json` | One file per cell. A cell is a selection. |

## The one replaceable step

`generate.sh` has a fixed contract — a selection file in, a zip out — and phase 0 implements
it by booting the server and calling `POST /api/v1/generate`.
[`kitbash-17`](../docs/tasks/phase-1-recipe-engine/kitbash-17-cli-module.md) adds the CLI and
`kitbash-18` replaces the body of that script with a CLI invocation. Nothing else about the
job changes, which is the point of putting it behind a script instead of inlining curl into
two CI files.

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
| Budget | 600 s, asserted (`KITBASH_CELL_BUDGET`) | Logged every run, not only when breached, so the trend is visible before it is a problem. |

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

Add a selection to `selections/`, and add one line to both CI files. That is the whole
process until `kitbash-18` turns it into a matrix.
