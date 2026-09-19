# `/web` — the React application

The bootstrapper's own web app: React 19 + TypeScript + Vite + pnpm, Tailwind and
shadcn/ui, TanStack Query for catalog data and Zustand for the selection (plan §5, §9).

Empty until [`kitbash-4-web-shell-generate-button`](../docs/tasks/phase-0-walking-skeleton/kitbash-4-web-shell-generate-button.md),
which stands up the toolchain and one disposable form. The metadata-driven wizard that
replaces that form is [`kitbash-16`](../docs/tasks/phase-1-recipe-engine/kitbash-16-metadata-driven-wizard.md).

**What does not belong here:** anything that knows a technology's name. The wizard renders
whatever `/api/v1/metadata` describes; a hardcoded "Spring Boot" string in this directory is
a bug (§19).
