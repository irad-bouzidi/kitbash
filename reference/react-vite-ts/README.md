# storefront

## The stack

- **Frontend** — React 19 with TypeScript and Vite, served by nginx in a non-root container
- **Containers** — a compose file wiring the stack together, with healthchecks and non-root images
<!-- kitbash:stack -->

## Start it

```bash
docker compose up --build
```

That brings everything up, waits for the database to be healthy, and starts the service on
**http://localhost:8080** — documented at `/swagger-ui.html`, health at `/actuator/health`.

<!-- kitbash:start -->

## Work on it

```bash
cp .env.example .env          # then export it, or let your IDE load it
```

```bash
cd frontend
pnpm install
pnpm dev                      # http://localhost:5173, /api proxied to the backend
pnpm build                    # typecheck and production bundle
pnpm test                     # component tests, jsdom
pnpm lint                     # ESLint and Prettier, both failing the build
```

```bash
docker compose up -d db       # just the database, to run the app from your IDE
```

<!-- kitbash:work -->

## How it is laid out

```
frontend/src/
  api/      the only file that knows the shape of the API
  pages/    one page per route, with its test beside it
```

<!-- kitbash:layout -->

## Rules worth knowing before editing

- **The API lives behind `frontend/src/api/`.** A `fetch` in a component is a call the
  generated typed client cannot replace, and replacing that one file is the whole point
  of the option.
- **Requests are same-origin.** The dev server proxies `/api`, and so does the container's
  nginx, so nothing needs CORS.
<!-- kitbash:rules -->
