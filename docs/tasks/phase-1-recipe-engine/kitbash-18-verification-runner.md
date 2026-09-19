# kitbash-18-verification-runner

**Phase** 1 — Recipe engine · **Depends on** `kitbash-14-recipe-frontend-react-vite`, `kitbash-17-cli-module` · **Plan** §12, §13

## Goal

One runner, built once, that the merge-request job, the nightly matrix and (later) on-demand
user verification all drive with different inputs. Four cells is enough to start; the shape is
what matters.

## Context

§12 calls this the highest-value infrastructure in the project and says it exists from phase 1,
not retrofitted. The Build Plan's nightly matrix and the Implementation Plan's per-generation
build validation are the same machine with different inputs, so it gets built once, with the
third trigger (`kitbash-37`) plugging into the same runner.

The decisive design point: a cell runs the stack's **real build and test commands**, not a
snapshot test of rendered text. A generator whose output merely matches a golden file is a
generator that can emit projects nobody can compile.

## Scope

- `verify` module: cell definition (a selection plus the commands its stack requires), runner,
  result model, log capture, timing.
- Per-cell execution:
  1. generate via `cli`,
  2. unzip into a workspace,
  3. run the stack's real commands in an ecosystem container — `./gradlew build`,
     `pnpm build && pnpm test`.
- Ecosystem images under `/verification/images/` — JVM (from `kitbash-5`) and Node.
- Isolation per §12/§13: disposable containers, **no network beyond the dependency proxy**, CPU
  and memory caps, hard timeout. **Never on the API host.**
- Triggers wired now:
  - **merge request** — ~8 representative cells, under 10 minutes,
  - **nightly** — the four enumerated cells of the current catalog.
- Failure output includes the exact selection JSON and a one-line local reproduction command.
- A static status page under `/verification` showing pass/fail and duration per cell.

## Out of scope

Sharding, the full ~96-cell enumeration, history-fed cells and the on-demand API — `kitbash-35`,
`kitbash-40`, `kitbash-37`.

## Implementation notes

- Model a cell as data, not code, so `kitbash-35` can enumerate them and `kitbash-37` can
  construct one from a user's selection without a new abstraction.
- Capture logs per cell into a file from the start; the on-demand job needs to serve exactly
  those logs to a user later.
- Record duration per cell from the first run. The 20-minute nightly budget in phase 3 is only
  defensible with a timing history.

## Files and modules touched

`/server/verify/**`, `/verification/images/node/**`, `/verification/cells/**`,
`/.gitlab-ci.yml`.

## Done when

- Four cells run green nightly.
- A deliberately broken recipe turns the nightly red, and the log contains a selection JSON
  that reproduces the failure locally in one command.
- The MR job stays under 10 minutes.
