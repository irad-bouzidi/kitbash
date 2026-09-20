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

## How it is laid out

```
src/
  catalog/   fetch and typed access to /api/v1/metadata (TanStack Query)
  wizard/
    fields/          one component per option type
    FieldRenderer    option type -> component; the only switch in the application
    useSelection     the selection, and its reflection in the URL (Zustand)
    useValidation    debounced POST /validate
    useFieldErrors   a Zod schema built at runtime from the catalog's own patterns
  lib/api/   the typed client; schema.d.ts is generated, not edited
```

`schema.d.ts` comes from the server's OpenAPI document. Regenerate it after an API change with
`./gradlew :api:test -Dkitbash.openapi.update=true` from `server/`, then `pnpm gen:api` here. A
test fails when the checked-in spec and the server disagree.

## Two things to get right and keep right

**The download is a navigation, not a fetch.** The Generate button submits a hidden HTML form
to `POST /api/v1/generate`, so the browser downloads the file itself: its own progress
indicator, its own resume, no multi-megabyte blob held in the tab (§9). `fetch` +
`createObjectURL` is easy to reach for and shows the user nothing while a zip is being built.
A browser cannot post JSON through a real form, so the envelope travels as one
`selection` field holding the same JSON the API takes as a body.

**Nothing here may know a technology's name.** The wizard renders whatever
`/api/v1/metadata` describes, so adding a recipe is a backend-only change (§9, §19). A
hardcoded `"Spring Boot"` in this directory is a bug, and
`src/catalog/noHardcodedCatalog.test.ts` fails on one: no option id and no label the catalog
declares may appear in `src/` outside the generated client. There are no exemptions, and the
test fails if somebody adds one that outlives the file it excuses.

The `e2e/` specs are the exception, and deliberately so: they drive the rendered page through a
real browser, so naming what is on it is the job.

If an option needs particular behaviour, that behaviour belongs to its **type**, declared in the
catalog. "Just for the package name field" is the exact failure this design exists to prevent.

## Design language

shadcn/ui defaults plus the theme tokens in `src/index.css`, nothing invented on top. Both
themes work from the first commit, because dark mode added later is a repaint of every
component. The theme is applied by an inline script in `index.html` before first paint, so a
dark-mode user never sees a white flash.
