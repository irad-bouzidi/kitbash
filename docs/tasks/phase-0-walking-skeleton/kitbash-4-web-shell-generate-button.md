# kitbash-4-web-shell-generate-button

**Phase** 0 — Walking skeleton · **Depends on** `kitbash-3-hardcoded-generate-endpoint` · **Plan** §5, §9

## Goal

The web application exists, is themed, is wired to the server, and can trigger a real
download. One button, one working path.

## Context

This form is expected to be deleted by `kitbash-16`, which replaces it with the
metadata-driven wizard. What survives is the toolchain: Vite, Tailwind, shadcn/ui, the test
runners, the dev proxy and the compose services. Setting those up while there is one page to
verify against is much cheaper than doing it under a real wizard.

One interaction detail is worth getting right immediately because it is easy to get wrong
permanently: the download must be a navigation, not a `fetch` + blob (§9).

## Scope

- `/web` — React 19 + TypeScript + Vite + pnpm.
- Tailwind + shadcn/ui installed, with light and dark themes both working from the first
  commit. Dark mode added later is a repaint of every component.
- ESLint + Prettier wired, and a `pnpm lint` script that CI runs.
- One page: `projectName`, `groupId`, `packageName`, `javaVersion` fields and a **Generate**
  button, posting the §7 envelope.
- **Download via form post or anchor navigation, not `fetch` + blob**, so the browser shows
  native download progress (§9).
- Vite dev server proxy to the API so there is no CORS configuration in development.
- Root `compose.yaml` gains `server` and `web` services, so `docker compose up` runs the whole
  thing.
- Vitest configured, with one component test that actually asserts something.
- Playwright configured, with one smoke test: fill the form, click Generate, assert a zip
  download occurs.

## Out of scope

No catalog fetch, no `/validate` call, no option groups, no URL sync, no Zustand store, no
TanStack Query — all of that belongs to `kitbash-16` and would be written twice.

## Implementation notes

- Do not invent a design system here. shadcn/ui defaults plus the theme tokens are the whole
  design language; the wizard task consumes them.
- Keep the page in a file named so its disposability is obvious (`Phase0Form.tsx`), and do not
  build shared abstractions out of it.
- The Playwright download assertion should check the file is a non-empty zip, not merely that a
  download event fired.

## Files and modules touched

`/web/**`, `/compose.yaml`, CI job for `pnpm lint && pnpm test`.

## Done when

- `docker compose up`, open the page, click Generate, and the browser downloads a zip that
  builds.
- `pnpm lint`, `pnpm test` and the Playwright smoke test are green in CI.
