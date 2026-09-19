# kitbash-41-metrics-and-structured-logs

**Phase** 4 — Validation and polish · **Depends on** `kitbash-27-zip-cache-and-retention` · **Plan** §10, §14

## Goal

The observability the bootstrapper itself needs — and the privacy rule that governs it.

## Context

§14 lists the metrics exactly, and §10 sets the constraint they operate under: *logs and metrics
carry hashes and recipe ids only — never package or project names.* That constraint is easy to
state and easy to violate, because the project name is the most natural thing to log when
debugging. Hence a test that enforces it rather than a convention that asks for it.

## Scope

**Metrics** (§14):

- generation duration and outcome,
- cache hit rate,
- recipe usage,
- validation failure reasons by type (from `kitbash-39`),
- matrix pass rate per cell,
- verification queue depth and run outcomes (from `kitbash-37`).

**Logs:**

- Structured JSON with a correlation id propagated end to end — API, generator, job runner.
- Log levels that make a production incident readable: one line per generation at info, detail at
  debug.

**Privacy:**

- **No project or package names in logs or metrics**, enforced by a test that generates a project
  with a distinctive name and greps the emitted log and metric output for it.
- Selection hashes and recipe ids are the identifiers used everywhere instead.

**Dashboard:**

- A small operator dashboard definition checked into `/docs`, covering the metrics above plus
  error rate by type.

## Out of scope

No alerting rules, no paging policy, no tracing backend — those are deployment concerns for
whoever runs it.

## Implementation notes

- The name-leak test is the deliverable most likely to be quietly deleted when it becomes
  inconvenient. Make its failure message explain *why* the rule exists, citing §10.
- Cardinality: recipe usage tagged by recipe id is fine; anything tagged by selection hash is not
  a metric, it is a log line.
- Correlation id must survive the boundary into the verification job, or a failed user-triggered
  verify cannot be traced back to its request.

## Files and modules touched

`/server/api/**` (metrics, logging config), `/server/core/**` (instrumentation points),
`/docs/dashboard.json` or equivalent.

## Done when

- Every metric in the §14 list is emitted and visible on the dashboard.
- The name-leak test is green, and demonstrably red when a name is logged deliberately.
- A single request can be traced end to end by correlation id, including into a verification run.
