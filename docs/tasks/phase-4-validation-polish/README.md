# Phase 4 — Validation and polish

The verification machine gets its third trigger, errors become a surface rather than a stack
trace, the nightly run starts testing what people actually build, and the CLI ships.

The through-line of this phase is that the system stops being opaque: a user can verify their own
combination and read the log, a failure names the stage and the recipe and the file, and the
operators can see generation outcomes, cache hit rate and matrix pass rate without reading logs.

**Phase exit test:** a user can verify their own combination from the UI and read the build log.

| # | Branch | Depends on |
| --- | --- | --- |
| 37 | [`kitbash-37-on-demand-verify-job`](kitbash-37-on-demand-verify-job.md) | 35, 22 |
| 38 | [`kitbash-38-wizard-verification-badges`](kitbash-38-wizard-verification-badges.md) | 37 |
| 39 | [`kitbash-39-structured-error-surface`](kitbash-39-structured-error-surface.md) | 6, 16 |
| 40 | [`kitbash-40-history-fed-nightly-matrix`](kitbash-40-history-fed-nightly-matrix.md) | 27, 35 |
| 41 | [`kitbash-41-metrics-and-structured-logs`](kitbash-41-metrics-and-structured-logs.md) | 27 |
| 42 | [`kitbash-42-cli-binary-release`](kitbash-42-cli-binary-release.md) | 17, 39 |

39, 40, 41 and 42 are independent of each other.
