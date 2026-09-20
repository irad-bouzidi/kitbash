-- The §10 data model, and deliberately nothing else.
--
-- The most important property of this schema is what it does not contain. There is no
-- `technology`, `technology_version` or `architecture` table, because the recipe catalog
-- lives in git: reviewable, diffable, and versioned with the code that renders it. Putting
-- it behind a migration and an admin CRUD screen buys nothing that a pull request does not
-- already give, and a database-backed catalog is only needed for user-uploaded recipes —
-- a phase 5 question with real security weight of its own (§10, kitbash-47).
--
-- So every table here describes what a user did. None describes what the system can do.

-- A saved selection somebody named. `revision` rather than an update in place: a preset is
-- a thing people share links to, and silently changing what a shared link resolves to is a
-- worse failure than an extra row (§10).
create table preset (
    id             uuid primary key,
    owner_id       uuid        not null,
    name           text        not null,
    description    text,
    visibility     text        not null, -- private | team | public
    selection      jsonb       not null,
    version_policy text        not null, -- track_latest | pinned
    pinned_recipes jsonb,                -- recipe id -> version; null while tracking latest
    revision       int         not null default 1,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    unique (owner_id, name, revision)
);

-- One generation that happened. `lock` is the valuable column: it is what makes a row
-- replayable long after its zip has expired, and it is small enough that keeping it forever
-- costs nothing.
--
-- `preset_id` is `on delete set null` rather than cascading, because deleting a preset must
-- not delete the history of what it produced. The row outlives its origin.
create table generation (
    id             uuid primary key,
    owner_id       uuid,
    preset_id      uuid references preset (id) on delete set null,
    -- Kept so a user can recognise their own rows, and deleted with the record. §10 is
    -- explicit that it must never reach logs or metrics, which carry hashes and recipe ids.
    project_name   text        not null,
    selection      jsonb       not null,
    lock           jsonb       not null, -- recipe id -> exact version
    catalog_digest text        not null,
    selection_hash text        not null,
    artifact_key   text,                 -- object store key; null once the zip has expired
    status         text        not null,
    duration_ms    int,
    size_bytes     int,
    created_at     timestamptz not null,
    expires_at     timestamptz,          -- created_at + 30d, or null when kept
    kept           boolean     not null default false
);

-- The history query: one user's rows, newest first.
create index generation_owner_created_at_idx on generation (owner_id, created_at desc);

-- Not for a user-facing query. §10 uses popular selection hashes to decide which stacks the
-- verification matrix should prioritise, and kitbash-27 uses it to find the rows a cached
-- artifact belongs to.
create index generation_selection_hash_idx on generation (selection_hash);

-- A selection somebody sent to somebody else. No owner: the token is the capability, and a
-- share link that required an account would not be a share link.
create table share_link (
    token      text primary key,
    selection  jsonb       not null,
    created_at timestamptz not null,
    expires_at timestamptz
);

-- A verification of one selection against one catalog. `requested_by` is null for the
-- nightly matrix, which nobody requested.
create table verification_run (
    id             uuid primary key,
    requested_by   uuid,
    selection      jsonb not null,
    lock           jsonb not null,
    selection_hash text  not null,
    catalog_digest text  not null,
    status         text  not null, -- pending | running | passed | failed
    log_key        text,
    started_at     timestamptz,
    finished_at    timestamptz,
    expires_at     timestamptz
);

-- The index that makes kitbash-37's dedupe correct rather than merely likely.
--
-- Two requests for the same selection and the same catalog must produce one run, and
-- checking for an existing row before inserting is a race with a window wide enough to lose
-- under ordinary load. A partial unique index makes the second insert fail in the database,
-- where the check and the write are the same operation.
--
-- Partial, because `failed` must not block a retry: a run that failed is a result somebody
-- may want to reproduce after fixing the recipe, and only a run that is pending, running or
-- already passed makes a second one redundant.
--
-- It is created in the same migration as the table on purpose. A migration that created the
-- table first would leave a window — however short — in which duplicates could be written.
create unique index verification_run_dedupe_idx
    on verification_run (selection_hash, catalog_digest)
    where status in ('pending', 'running', 'passed');
