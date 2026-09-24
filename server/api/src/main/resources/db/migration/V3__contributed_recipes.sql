-- kitbash-47: the two tables that cross §10's line, and why they are only two.
--
-- V1 says it plainly: "every table here describes what a user did. None describes what the
-- system can do." `contributed_recipe` breaks that, and the break is the decision recorded
-- in docs/adr/0004-contributed-recipes-accepted.md rather than an oversight.
--
-- What survives of the rule is the shape. A contributed recipe is stored with its full
-- content and its review state, so the row is a record of *a submission somebody made and a
-- reviewer approved* — an event with people attached — rather than a configuration screen
-- for what the generator supports. Shipped recipes are still in git and still are not here.
-- `SchemaTest.theCatalogIsNotInTheDatabase` now asserts these two are the only exceptions,
-- so the third has to argue for itself.

-- One submission. Immutable except for its review state: a recipe's content never changes
-- in place, because a generation's lock names a version and a digest and both have to keep
-- meaning what they meant. A revised recipe is a new row at a new version.
create table contributed_recipe (
    id             uuid primary key,
    -- '@namespace/name'. Unique across versions is wrong — the same recipe exists at many
    -- versions — so the constraint is on the pair, which is also what the lock records.
    recipe_id      text        not null,
    namespace      text        not null,
    version        text        not null,
    -- recipe.yaml as submitted, and the files/ tree as a path -> content map. Stored rather
    -- than referenced: the thing a reviewer approved has to be the thing that renders, and
    -- an external blob store would put a second system between the two.
    manifest       text        not null,
    content        jsonb       not null,
    -- sha256 over the same sorted (path, bytes) walk CatalogLoader uses for a git recipe, so
    -- the two halves of the catalog digest are computed the same way and a contributed
    -- recipe's identity does not depend on which half it lives in.
    content_hash   text        not null,
    status         text        not null, -- submitted | verified | approved | revoked
    submitted_by   uuid        not null,
    submitted_at   timestamptz not null,
    -- §47: approval is a human action recorded against a named reviewer. Nullable because a
    -- row exists before it is reviewed, not because approval is optional.
    reviewed_by    uuid,
    reviewed_at    timestamptz,
    -- The verification run that gated approval. §47 requires the matrix to pass *before*
    -- approval, not after, and this is what makes that checkable afterwards.
    verified_by_run uuid,
    revoked_by     uuid,
    revoked_at     timestamptz,
    revoked_reason text,
    unique (recipe_id, version)
);

-- The catalog assembly query: everything visible, which is approved and not revoked. A
-- partial index because the other states are a review queue, not a hot path.
create index contributed_recipe_visible_idx
    on contributed_recipe (recipe_id)
    where status = 'approved';

-- The review queue, oldest first: a submission nobody has looked at is the one that has been
-- waiting longest, and a reviewer opening this page wants that one.
create index contributed_recipe_queue_idx
    on contributed_recipe (submitted_at)
    where status in ('submitted', 'verified');

-- §47: revocation "pulls a recipe and flags the generations that used it".
--
-- A table rather than a column on `generation`, because one generation can be flagged by two
-- revocations and a column would make the second overwrite the first. The row a user needs
-- to see is "this project contains code from a recipe that was withdrawn, here is which one
-- and why" — and if two were withdrawn they need both.
--
-- Written at revocation time rather than computed on read. The lock is jsonb and the
-- containment query is cheap, but a flag that only exists while somebody is looking is a
-- flag that no notification, export or audit can ever be built on.
create table generation_flag (
    generation_id  uuid        not null references generation (id) on delete cascade,
    recipe_id      text        not null,
    reason         text        not null,
    flagged_at     timestamptz not null,
    primary key (generation_id, recipe_id)
);

-- Every flag on one generation, for the history row that has to render it.
create index generation_flag_generation_idx on generation_flag (generation_id);
