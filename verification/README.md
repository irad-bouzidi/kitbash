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
docker build -t kitbash/verify-ci:latest   -f verification/images/ci/Dockerfile   .

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

Four of the eighteen cells today are the four §17 asks for: backend only, frontend only, both, and
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

The typed client (§33) brings the three cells that are different in kind. `full-stack-typed` is the
first to set **`sharedWorkspace`**: its client is produced by the JVM build and consumed by the
frontend build, which is two ecosystems and one working tree — so the steps share a Docker volume
instead of each starting from a clean copy. Nothing §13 asks for is weakened: the volume is created
per cell and destroyed with it, the project still arrives read-only at `/input`, and every limit is
still per container.

`full-stack-typed-kotlin` is the same cell with the Kotlin backend, and it is the one place the
language axis is not covered pairwise. Everywhere else the two languages emit files from two
directories and a cell proves one of them; here the client is derived from the backend's own types,
so the languages genuinely produce different TypeScript. A Kotlin `val id: Long?` reaches the
OpenAPI document where a Java `Long` does not — §36 found that with the Java cell green and
twenty-four Kotlin cells of the nightly matrix red.

`typed-client-contract` is the phase exit criterion, and **the only cell that passes by making
something fail**: it renames a field on a backend DTO, regenerates the client, and requires
`pnpm typecheck` to stop compiling — `! pnpm typecheck`, so a green cell means the binding is real.
Removing the rename turns it red, which is how we know it is not vacuous.

The two CI cells (§34) are the ones that check a file instead of building one, and they exist
because a CI recipe fails silently: a rendered pipeline is valid YAML, gets committed, and nobody
notices it never ran. `ci-github` runs `actionlint`, which also shellchecks every `run:` block;
`ci-gitlab` runs GitLab's published schema offline, because GitLab's own lint is an API on an
instance a cell has no token for and no business calling. `ci-gitlab` earned its place immediately:
it found a job named `image` — a reserved keyword — in a pipeline shipped since phase 1.

Auth (§31) and observability (§32) are not further axes. Both add the same files whichever
architecture, language or build tool is chosen, so each is covered by the two reference projects
that carry it plus one cell for the combination no reference has: `full-stack-auth` for auth's
browser half, and `backend-observability` for metrics with auth off.

## The full matrix, and the representative one

Two sources of cells, one runner.

**A pull request** runs the cells checked in under `cells/` — the representative set, chosen so
every axis is covered at least once and every pair of the three structural axes is built
(`CellTest.coversEveryPair` asserts that rather than trusting the list). Seventeen of them today.

**The nightly** ignores those and enumerates the matrix from the catalog:

```bash
./gradlew :verify:runMatrix -Pkitbash.enumerate=true
```

Ninety-eight cells, derived rather than written down, so a recipe added tomorrow expands the matrix
tomorrow. What is enumerated in full is what changes the *shape* of a generated project — build
tool × backend × architecture × frontend × containers × CI, which is ninety-six — and the feature
toggles ride along on bits of the cell index, so each is on in half the cells and every pair occurs
at both settings of the other. Pairwise coverage by construction, at no extra cells.

Two things are deliberately not enumerated. The **database** is not an axis: every backend requires
one, so a backend without a database is not a combination but an invalid selection. And
**frontend-only is sampled** — two cells — which is the one exemption §18 grants by name, because
supporting a standalone frontend costs a single conditional and should not double the matrix.

`Enumeration.EXPECTED_CELLS` is asserted. A recipe declared into one slot too many turns
ninety-eight cells into nine hundred, and the symptom would otherwise be a nightly that quietly
stops finishing rather than anything going red.

The budget the runner prints is **one shard's** wall clock, which is the number that matters: the
shards run in parallel, so the job is as slow as its slowest one. Ten minutes for a merge request,
twenty for the nightly, both warm, per §12. Going over prints a loud line and does not turn the
matrix red — a red cell should mean a generated project is broken, not that the runner had a slow
afternoon, and a CI job timeout already catches the runaway case.

## Sharding

```bash
./gradlew :verify:runMatrix -Pkitbash.enumerate=true -Pkitbash.shard=3/12
```

Both jobs shard, and the shards run as parallel runners. Assignment is round-robin over id-sorted
cells, which means two things worth having: a cell is always in the same shard, so a flaky one can
be rerun on its own; and fast and slow cells are mixed into every shard, which contiguous blocks
would not do — every Maven cell would land together and shard durations would depend on where the
alphabet fell.

**By cell, not by ecosystem.** §35 suggested sharding by ecosystem; ecosystems are not the same
size, and nearly every cell has a JVM step, so that shard would do almost all the work while the
others finished in seconds.

## What is here

| Path | What it is |
| --- | --- |
| `cells/*.json` | One file per cell: a selection, its triggers, and one step per ecosystem. |
| `selections/*.json` | The §7 envelopes the cells generate from. |
| `images/jvm/**` | JDK 21, warm Gradle, Kotlin and Maven caches, `unzip`, `git`. Nothing else. |
| `images/node/**` | Node 24, pnpm with a warm store, `unzip`, `git`. Nothing else. |
| `images/ci/**` | `actionlint`, `check-jsonschema` and `osv-scanner`. The only image that inspects rather than builds. |
| `images/mobile/**` | Node 24, npm with a warm cache, the Expo toolchain. **Nothing native** — see below. |
| `generate.sh` | Selection in, zip out. **The one replaceable step** — see below. |
| `build/enumerated/` | The nightly's selections, written by the enumeration at run time. Output, not source. |
| `build/requested/` | The same, for a selection somebody asked about through `POST /api/v1/verify`. |
| `run-cell.sh` | `run-cell.sh <cell-id>` — one cell, for reproducing a failure. |
| `build/` | Output: `status.html`, `status.json` and a log per cell. Not checked in. |
| `build/verification.json` | The same run in the shape the wizard's badges need, with each cell's options. |
| `build/history-cells.tsv` | What §40 offered this run, with usage counts, for the next run to diff against. |

The runner itself is `server/verify`, in Java, because `kitbash-37` has to construct a cell from
a user's selection and serve its logs back — which wants a result model rather than a shell
script's exit code.

## What the wizard reads

§12 has the results feeding two audiences: `status.html` for a person, and the wizard's badges for
someone choosing. `verification.json` is the second, written from the same run as the page so the
two can never disagree about a cell.

It carries one thing the status page does not — **the options each cell was generated from** —
because a badge decorates an option *pairing*, and a cell's outcome is meaningless for that purpose
without knowing which pairings it covered. The API aggregates them: every unordered pair of chosen
values gets the worst verdict of every cell containing it, which is what puts §36's warning on
`backend=kotlin & typedClient=true` and on neither half alone.

A shard writes its own twelfth and merging is addition, because no cell appears in two shards.

The runner still has no database. §12 keeps it independent of the API, its auth and its
persistence, so it publishes a document and the API reads it — which is why the nightly's results
reach a user as badges rather than as rows warming the on-demand dedupe.

## The stacks people actually build

§12 calls history-fed verification *the piece neither plan had*, and the gap it closes is specific:
an enumerated matrix tests the **catalog's cross-product**, which is not the same set as the stacks
a team relies on. A combination that is unusual on paper but is one team's house standard deserves
nightly coverage more than a cell nobody has ever generated.

So the nightly asks the API for the twenty most-generated selections and adds the ones the
cross-product misses. Three rules, all of them about not making the nightly worse:

**Nothing identifying crosses.** §10: *only hashes, recipe ids and selections cross into the runner
— never project or package names.* The API replaces every variable with the enumeration's neutral
set before the list leaves it, by **building a new envelope rather than removing fields** — a
deny-list is a list somebody has to extend the next time a recipe declares a variable, and
forgetting is silent. The consequence is that a history cell hashes differently from the generation
it came from; the original hash travels beside it as a label.

**Deduplication is by shape, not by hash.** Because of the above, a history cell's hash can never
equal an enumerated cell's — comparing hashes would deduplicate nothing and the nightly would
quietly double. The shape is the selected options, sorted.

**A removed recipe is a catalog change, not a regression.** A popular stack naming a recipe this
catalog no longer has is skipped with a note. Failing it would make every deletion look like a
broken nightly.

History cells are added **before sharding**, so they count toward the budget and the shards absorb
them (§35) rather than one runner taking twenty extra cells. Their ids begin `h-`, which is how the
status page labels them — §40 wants it obvious which failures affect real users.

The whole thing is optional. §12's independence rule is about *running* a cell: nothing here touches
a database and generation still goes through the CLI. Choosing which cells to run is a different
question, and history is the only honest answer to it — but a nightly that fell over because the API
was unreachable would be a nightly that stops reporting that the catalog is broken, which is the one
thing it exists to do. No `KITBASH_HISTORY_URL`, no history cells, and the report says so.

Each run writes `build/history-cells.tsv` — every stack that was offered, its usage count, and
whether it ran. A run cannot compare itself to the previous one, so the workflow diffs it against
the last nightly's artifact. A stack dropping out because usage moved is information; one dropping
out because a recipe was renamed is a coverage hole, and from inside a single run the two look
identical.

## What a mobile cell proves, and what it does not

The mobile image has **no Android SDK and no Xcode**, on purpose. A cell there runs `npm ci`,
`tsc --noEmit`, `jest` and `expo export`: the TypeScript compiles, the screen renders and handles
its states, and Metro produces a Hermes bundle.

It does **not** prove the app launches on a device, that a gesture works, or that anything is laid
out correctly. §45 asks for that boundary to be explicit, and the reason is that a green badge
implying otherwise would be a claim the matrix cannot back.

What the boundary buys is a cell that finishes in about thirty seconds. An emulator-backed cell
needs a KVM-capable host, several gigabytes and minutes per run — for a signal nobody acts on
anyway, because nobody fixes a layout bug from a nightly.

The two cells are **sampled, not enumerated**, following §18's precedent for the standalone
frontend: `mobile-only` and `mobile-full-stack`. Mobile is independent of the build-tool and
architecture axes, so enumerating it would multiply a ninety-eight cell matrix by a whole ecosystem
for combinations that differ in nothing mobile touches.

npm rather than pnpm in this image alone. React Native resolves native modules by walking up from a
package's real location, and pnpm's symlinked store breaks that walk for a class of libraries —
a generated project needing `node-linker=hoisted` to work is one whose first `npm install`, the
command every Expo document tells you to run, does something different from what CI did.

## On demand, for a combination nobody enumerated

`POST /api/v1/verify` answers "does my combination actually build?" and it runs **here**, through
`CellRunner`, in the same images, under the same §13 limits. That is not a convenience: a user's
selection must not be verified more gently than one the catalog happened to enumerate, or a green
answer would mean something weaker than a green cell.

The join is two small classes. `CellSteps` derives the build commands from a selection's options
and is the *only* place that does — the nightly calls it too, so the two cannot drift into
disagreeing about what "green" means. `RequestedCell` writes the requested selection under
`build/requested/` and hands back an ordinary `Cell`, which is why `generate.sh`'s contract did not
have to change.

What is different is the deadline. The matrix has a budget per shard; an on-demand run has fifteen
minutes promised to a person who is waiting, so `CellRunner.run(cell, deadline)` gives each step
whatever is left of it rather than the full per-step timeout, and refuses to start a step that
cannot finish. A step started with one second left would be killed one second later and report a
timeout of its own, which reads as "the build is slow" rather than "the run ran out".

Everything about *who* gets a container — the dedupe, the pool of four, one run per person, the
queue that refuses rather than grows — is the API's, in `server/api/**/verify`. This directory's
job is that the build is real.

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
