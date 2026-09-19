# kitbash-5-ci-generated-build-gate

**Phase** 0 — Walking skeleton · **Depends on** `kitbash-3-hardcoded-generate-endpoint` · **Plan** §12, §13, §17

## Goal

The phase exit test runs on every merge request, in a container, so the skeleton cannot
silently rot. This is the first cell of what becomes the verification matrix.

## Context

§12 calls verification the highest-value infrastructure in the project and insists it exists
from the start rather than being retrofitted. This task is that principle at its smallest
useful size: one cell, one ecosystem image, one build. `kitbash-18` generalizes it into a
runner; `kitbash-35` scales it to the full matrix. The image built here is reused by both.

The rule that must hold from the very first job: **never run generated builds on the API
host** (§12).

## Scope

- CI job `generated-build`:
  1. generate a zip (call the generator directly, or boot the server and `curl` it),
  2. unzip into a workspace,
  3. run `./gradlew build` inside a JVM ecosystem container.
- `/verification/images/jvm/Dockerfile` — the first ecosystem image: JDK 21, a warm Gradle
  cache, nothing else.
- Container isolation per §13: no network beyond a dependency proxy/mirror, a CPU and memory
  cap, and a hard timeout.
- On failure the job prints the **exact selection JSON** used, so reproducing locally is one
  command — and prints that command too.
- Job wall-clock budget asserted at under 10 minutes, with the measured time logged so the
  trend is visible before it becomes a problem.
- `/verification/README.md` describing how to run a cell locally with the same image.

## Out of scope

No matrix, no nightly schedule, no status page, no sharding, no Node ecosystem image — those
arrive with `kitbash-18` and `kitbash-35`.

## Implementation notes

- Generating via the server in this phase is acceptable, but structure the job so the
  generation step is a single replaceable command: `kitbash-18` swaps it for the `cli`
  invocation and nothing else about the job should change.
- Prefer a dependency proxy over a fully offline cache — an offline cache silently goes stale
  and then hides real breakage.

## Files and modules touched

`/.gitlab-ci.yml`, `/verification/images/jvm/**`, `/verification/README.md`.

## Done when

- The MR pipeline goes red when the generated project stops compiling, demonstrated in the MR
  discussion with a deliberately broken commit and its failing job link.
- The failing job's log contains the selection JSON and a one-line local reproduction command.
- The job completes in under 10 minutes.
