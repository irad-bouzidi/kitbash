# The data model

Four tables, and deliberately no more. The migrations that build them are in
[`server/api/src/main/resources/db/migration`](../server/api/src/main/resources/db/migration), and
[`schema.snapshot.txt`](../server/api/src/test/resources/schema.snapshot.txt) is what they produce,
checked in so a schema change is reviewable as a shape rather than as a list of statements (§10).

## The catalog is not in the database

This is the most important property of the schema, and it is a property of what is *missing*.

There is no `technology` table, no `technology_version` and no `architecture`. Recipes live in the
repository, are validated at boot and held in memory: reviewable, diffable, and versioned with the
code that renders them. A recipe change is a pull request with a diff, tested by the verification
matrix before it merges.

Putting that catalog behind a migration and an admin CRUD screen would buy nothing a pull request
does not already give, and would cost the thing that makes the generator trustworthy — that what
produced your project is a commit you can read. A database-backed catalog is only needed for
**user-uploaded** recipes, which is a phase 5 question (`kitbash-47`) with real security weight of
its own.

`SchemaTest` asserts this rather than trusting it: a table whose name mentions a recipe, a
technology, an architecture or a catalog fails the build. Decisions about what *not* to build erode
one convenient table at a time, and only an assertion stops that.

**So every table here describes what a user did. None describes what the system can do.**

## The tables

| Table | What it is | The column that matters |
| --- | --- | --- |
| `preset` | a selection somebody named and saved | `revision` — presets are revised, never overwritten |
| `generation` | one generation that happened | `lock` — what makes the row replayable after its zip is gone |
| `share_link` | a selection somebody sent to somebody else | `token` — the capability itself |
| `verification_run` | a verification of one selection against one catalog | `status` — and the partial index over it |

### `preset`: revisions, not updates

`unique (owner_id, name, revision)`. A preset is a thing people link to and build on, and silently
changing what a link resolves to is a worse failure than an extra row. Saving computes the next
revision and inserts; the constraint is what makes that safe when two saves race, because the
database refuses the loser rather than letting both write revision 3 and leaving one unreachable.

`version_policy` and `pinned_recipes` go together: a preset that pins has to remember what it
pinned to, because the catalog will move on.

### `generation`: history outlives its origin

`preset_id references preset(id) on delete set null` — **set null, not cascade**. Deleting a preset
must not delete the record of what it produced. The row survives with a null origin, and it still
replays, because the lock is the valuable part and the lock is on the row.

Two indexes, each for a real query:

- `(owner_id, created_at desc)` — one user's history, newest first.
- `(selection_hash)` — not user-facing. §10 uses popular selection hashes to decide which stacks
  the verification matrix should prioritise, and `kitbash-27` uses it to find the rows a cached
  artifact belongs to.

`project_name` is kept because users need to recognise their own rows, and is deleted with the
record. **It must never reach a log or a metric**; those carry hashes and recipe ids only.

### `share_link`: the token is the capability

No owner column and no lookup by anything but the token. A share link that required an account
would not be a share link.

### `verification_run`: the partial index is the feature

```sql
create unique index verification_run_dedupe_idx
    on verification_run (selection_hash, catalog_digest)
    where status in ('pending', 'running', 'passed');
```

Two people asking to verify the same selection against the same catalog must produce **one** run.
"Check whether one exists, then insert" is a race with a window wide enough to lose under ordinary
load, so the claim is a single `insert … on conflict do nothing`: the database decides the winner
and the loser reads back the row that already exists. That is what makes `kitbash-37`'s dedupe
correct rather than merely likely.

It is **partial** for an equally deliberate reason. `failed` is outside the index, so a failed run
releases the dedupe and the same selection can be retried — a failure is a result somebody may want
to reproduce once the recipe is fixed. The other three states make a second run redundant.

The index is created in the same migration as the table, so no window ever exists in which
duplicates could be written.

## Retention: 30 days, uniformly

One number across all three stores, so the story is explainable: *anything older than a month is
gone unless you kept it.* Cached zips expire by an object-store lifecycle rule, rows and logs by a
nightly sweep on `expires_at` (`kitbash-27`).

Keeping a row is expressed as clearing `expires_at` rather than as a second policy. Expiring an
*artifact* while keeping its row is also allowed and is what the sweep does to a kept row's zip
after a year: the row still replays, it just re-renders.

Nothing reproducible is lost at expiry, because the lock is small and the lock is what matters.

## Running it

The database is **opt-in**, and that is a §10/§12 decision rather than a convenience. Half this
service — `/metadata`, `/validate`, `/generate` — needs no persistence at all, and §12 requires
verification to stay independent of the API's persistence so that a red cell means the generator is
broken rather than the deployment. A generator that refused to start without Postgres would make
the useful half hostage to the optional half.

So the JDBC and Flyway autoconfigurations are excluded unless the `persistence` profile is active:

```bash
docker compose up postgres

SPRING_PROFILES_ACTIVE=persistence \
KITBASH_DB_URL=jdbc:postgresql://localhost:5432/kitbash \
  ./gradlew :api:bootRun
```

`docker compose up` does this for you. Without the profile the service starts, serves the generator
endpoints, and has no repositories — which is exactly what every other test in `api` boots as.

## No ORM

`JdbcClient` and hand-written SQL. The tables are flat, the interesting columns are `jsonb` that
this layer never looks inside, and there is no entity graph to map — an ORM would add a dialect, a
mapping layer and a lazy-loading failure mode in exchange for nothing. Selections are stored whole,
with only the columns §10 names promoted and indexed: promoting more "because we might query them"
re-creates the catalog-in-the-database problem one column at a time.

## Testing

`StoreTest` runs against a real Postgres 16 through Testcontainers, because everything worth
testing here is a property of the database rather than of the code: the unique constraint, the
`on delete set null`, the partial index. A fake would assert only that the fake agrees with itself.

`PersistenceProfileTest` boots the actual application against an empty database and checks the two
things only configuration can get wrong — that Flyway ran, and that the repositories became beans.

These tests need a Docker daemon. On a host running Docker 29 or newer, Testcontainers' client
defaults to an API version the daemon refuses, so pass the daemon's own:

```bash
DOCKER_API_VERSION=$(docker version --format '{{.Server.APIVersion}}') ./gradlew :api:test
```
