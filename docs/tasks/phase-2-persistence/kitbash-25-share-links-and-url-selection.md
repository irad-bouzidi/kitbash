# kitbash-25-share-links-and-url-selection

**Phase** 2 — Persistence · **Depends on** `kitbash-16-metadata-driven-wizard` · **Plan** §8, §9, §13

## Goal

A configured wizard is a link. The URL form is the default because it survives without server
state; short tokens exist only for when the URL gets unwieldy.

## Context

§8 makes the ordering explicit: selections serialize into query parameters so a configured
wizard has a copyable URL, and short tokens are the fallback rather than the mechanism. That
ordering matters — a link that depends on a database row stops working when the row expires,
and the most common sharing case is pasting a configuration into a chat message that someone
opens five minutes later.

## Scope

- **URL encoding** — the canonical selection form (from `core`) encoded into query parameters,
  applied on every wizard change so back/forward navigation works and any link captures a
  configuration (§9).
- **`POST /api/v1/share`** — store a selection, return a short token.
- **`GET /api/v1/share/{token}`** — return the stored selection.
- Tokens are short, expiring (`expires_at`), and rate limited alongside `/generate` (§13).
- **Opening any shared configuration re-validates immediately** against the current catalog, so
  a selection that has since become invalid says so on arrival rather than at Generate.
- The share affordance in the UI offers the URL first and the token only when the URL exceeds a
  sensible length.

## Out of scope

No public gallery, no discovery, no permanent links. A share link is a convenience, not a
published artifact.

## Implementation notes

- Reuse the canonical selection encoding rather than inventing a second one; two encodings mean
  two migration paths when the envelope's schema version bumps.
- Apply the envelope migrations (`kitbash-6`) when loading an old share link or an old preset,
  which is exactly why that framework was written early.
- Validate on load through the same `/validate` path the wizard uses, so the messages are
  identical to the ones the user would see while editing.

## Files and modules touched

`/server/api/**` (share controller, repository), `/web/src/wizard/useSelection.ts`, share dialog.

## Done when

Copy the URL from a configured wizard, open it in a clean browser profile, and get the same
selection and the same validation result — and the same holds for a token link.
