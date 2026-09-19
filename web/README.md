# `/web` — the React application

React 19 + TypeScript + Vite + pnpm, Tailwind v4 and shadcn/ui, light and dark themes.

## Run it

```bash
pnpm install
pnpm dev            # http://localhost:5173, /api proxied to localhost:8080
```

Start the API beside it with `cd ../server && ./gradlew :api:bootRun`, or run the whole stack
with `docker compose up` from the repository root.

```bash
pnpm lint           # eslint + prettier --check
pnpm test           # vitest
pnpm test:e2e       # playwright; needs the API running
pnpm build          # typecheck + production build
```

## What is here, and what is temporary

`Phase0Form.tsx` is named so its disposability is obvious: it is one hardcoded form and
[`kitbash-16`](../docs/tasks/phase-1-recipe-engine/kitbash-16-metadata-driven-wizard.md)
deletes it. What survives is the toolchain — Vite, Tailwind, the shadcn/ui tokens, the test
runners, the dev proxy and the compose services. Setting those up against one page is much
cheaper than doing it under a real wizard.

Do not build shared abstractions out of that form.

## Two things to get right and keep right

**The download is a navigation, not a fetch.** The Generate button submits a hidden HTML form
to `POST /api/v1/generate`, so the browser downloads the file itself: its own progress
indicator, its own resume, no multi-megabyte blob held in the tab (§9). `fetch` +
`createObjectURL` is easy to reach for and shows the user nothing while a zip is being built.
A browser cannot post JSON through a real form, so the envelope travels as one
`selection` field holding the same JSON the API takes as a body.

**Nothing here may know a technology's name.** The wizard renders whatever
`/api/v1/metadata` describes, so adding a recipe is a backend-only change (§9, §19). A
hardcoded `"Spring Boot"` in this directory is a bug. The phase-0 form is the one exception
and it is on its way out.

## Design language

shadcn/ui defaults plus the theme tokens in `src/index.css`, nothing invented on top. Both
themes work from the first commit, because dark mode added later is a repaint of every
component. The theme is applied by an inline script in `index.html` before first paint, so a
dark-mode user never sees a white flash.
