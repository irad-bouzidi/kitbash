# `/docs`

| Path | What it holds |
| --- | --- |
| [`tasks/`](tasks/README.md) | The task backlog: one file per task, one task per branch, one branch per merge request. |
| [`adr/`](adr/README.md) | Architecture decision records. Why a choice was made, and what it costs. |
| [`linkedin/`](linkedin/README.md) | Build-in-public write-ups, one per phase. |
| [`auth.md`](auth.md) | Who may do what, the rate limits, and how to sign in on a laptop. |
| [`data-model.md`](data-model.md) | The four tables, and why the recipe catalog is not one of them. |
| [`reference-projects.md`](reference-projects.md) | How the projects under `/reference` are maintained, and every path the equality test ignores. |
| [`recipe-format.md`](recipe-format.md) | The shape of a recipe: `recipe.yaml`, its `files/` tree, and the patch operations. |
| [`authoring-recipes.md`](authoring-recipes.md) | How to write one: the workflow, the harness, and a worked example that CI builds. |
| [`errors.md`](errors.md) | Every §14 error code: what causes it, what fixes it, and where the rule is enforced. |
| [`dashboard.json`](dashboard.json) | The operator dashboard: every §14 metric, and the privacy rule they operate under. |
| [`cli.md`](cli.md) | The `kitbash` CLI: installing the released binary, and what its two version numbers mean. |
| [`create-stack.md`](create-stack.md) | `npx create-stack`: the terminal client, and why it is a rendering problem rather than a second product. |
| [`gitlab-push.md`](gitlab-push.md) | Pushing a build straight into a GitLab group: the token scope it asks for, and what happens when half of it works. |
| [`threat-model-contributed-recipes.md`](threat-model-contributed-recipes.md) | Why recipes the core team did not write were declined, and the four conditions that would reopen the question. |
| [`hooks.md`](hooks.md) | The repository's git hooks. |

Scope lives in [`../Project Bootstrapper — Unified Plan.md`](../Project%20Bootstrapper%20—%20Unified%20Plan.md);
the `§n` references throughout these documents point into it.
