# kitbash-26-preview-endpoint-and-viewer

**Phase** 2 — Persistence · **Depends on** `kitbash-12-pipeline-and-deterministic-package` · **Plan** §6, §8, §9

## Goal

See the tree before downloading it: a file tree with sizes, and the real rendered content of
any single file on demand.

## Context

§6 explains why this is cheap: `/preview` runs pipeline stages 1–5 and stops before packaging.
The pipeline was split into stages precisely so this endpoint is a partial run rather than a
second implementation.

The user value is trust. A generator you cannot look inside is one you try once; a generator
whose output you can inspect before committing to it is one a team standardizes on.

## Scope

- **`POST /api/v1/preview`** — returns the file tree with sizes; `?path=` returns one file's
  contents (§8).
- Runs stages 1–5 only. No packaging, no git skeleton, no persistence, no generation row.
- The same resource caps as generation apply (§13), enforced at the plan stage as usual.
- Binary files are reported as binary with their size, never streamed as text.
- Content is the **real rendered output**, not the template — that distinction is the whole
  point of the feature.
- **Web** `preview/`:
  - a lazily-loaded file tree,
  - a syntax-highlighted file pane,
  - a file fetched only when clicked (§9),
  - opened from the wizard's bottom bar, with the current selection.

## Out of scope

No editing of generated code — §1 lists in-browser editing as a non-goal. No diffing between
selections.

## Implementation notes

- Cache the plan, not the rendered text, between a tree request and the subsequent single-file
  requests; re-running stages 1–3 per file click is wasteful but re-rendering one file is cheap.
- Syntax highlighting should be chosen by file extension with a safe fallback — an unknown
  extension must render as plain text, not fail.
- Keep the tree response small: paths and sizes only. The temptation to inline small files
  makes the payload unpredictable.

## Files and modules touched

`/server/api/**` (preview controller), `/server/core/**` (preview entry point),
`/web/src/preview/**`.

## Done when

Preview of a full-stack selection renders the tree in well under a second, and a clicked file
shows its real rendered content with correct highlighting.
