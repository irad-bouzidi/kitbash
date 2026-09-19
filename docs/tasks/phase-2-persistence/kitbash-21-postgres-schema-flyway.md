# kitbash-21-postgres-schema-flyway

**Phase** 2 — Persistence · **Depends on** `kitbash-15-metadata-and-validate-endpoints` · **Plan** §10

## Goal

The §10 data model, migrated and indexed — and nothing else in the database.

## Context

The most important property of this schema is what it does *not* contain. §10 rejects the
`Technology` / `TechnologyVersion` / `Architecture` tables outright: putting the catalog behind
a migration and an admin CRUD screen buys nothing, and a DB-backed catalog is only needed for
user-uploaded recipes, which is a phase 5 question with real security weight.

So the tables here describe what users did, never what the system can do.

## Scope

Flyway migrations creating exactly the §10 objects:

- **`preset`** — `id`, `owner_id`, `name`, `description`, `visibility`, `selection` jsonb,
  `version_policy`, `pinned_recipes` jsonb, `revision`, timestamps,
  `unique (owner_id, name, revision)`.
- **`generation`** — `id`, `owner_id`, `preset_id` (`on delete set null`), `project_name`,
  `selection` jsonb, `lock` jsonb, `catalog_digest`, `selection_hash`, `artifact_key`,
  `status`, `duration_ms`, `size_bytes`, `created_at`, `expires_at`, `kept`; indexed on
  `(owner_id, created_at desc)` and on `selection_hash`.
- **`share_link`** — `token` primary key, `selection` jsonb, `created_at`, `expires_at`.
- **`verification_run`** — `id`, `requested_by` (null for matrix runs), `selection`, `lock`,
  `selection_hash`, `catalog_digest`, `status`, `log_key`, `started_at`, `finished_at`,
  `expires_at`, plus the **partial unique index** on `(selection_hash, catalog_digest)` where
  status is `pending`, `running` or `passed`. That index is what makes `kitbash-37`'s dedupe
  correct under concurrency rather than merely likely.
- Testcontainers-backed repository tests against Postgres 16.
- `docs/data-model.md` — the schema plus the reasoning for keeping the catalog in git.

## Out of scope

No API surface, no auth, no retention sweep job (that is `kitbash-27`). No ORM entity for
recipes, because there is no table for recipes.

## Implementation notes

- Store selections as `jsonb` with only the columns §10 names promoted and indexed. Promoting
  more columns "because we might query them" re-creates the catalog-in-the-database problem one
  column at a time.
- `project_name` is kept deliberately — users need to recognize their own rows — and is deleted
  with the record. It must never reach logs or metrics (§10).
- Write the partial unique index in the same migration as the table, so no window exists where
  duplicate verification runs can be created.

## Files and modules touched

`/server/api/src/main/resources/db/migration/**`, repository classes and tests,
`/docs/data-model.md`.

## Done when

- Migrations apply from empty on Postgres 16 and are idempotent on re-run.
- The schema matches §10 field for field, asserted by a schema snapshot test.
- A test asserts no table describes recipes, technologies or architectures.
