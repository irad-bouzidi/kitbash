-- kitbash-46: where a generation was pushed, when it was pushed anywhere.
--
-- The first migration after the V1 baseline, and deliberately one column rather than a table. A
-- push is not a separate event to a generation — it is how one generation was taken delivery of,
-- alongside the zip. A `push` table would need its own foreign key, its own retention rule and its
-- own answer to "which generation is this", all to record one URL.
--
-- Nullable, because most generations are downloads and always will be: §46 keeps the zip the
-- default and says the push must not degrade it.
alter table generation
    add column pushed_project_url text;

-- No index. Nothing queries by URL — it is read back with the row a user is already looking at,
-- and an index on a column with one lookup pattern already served by the primary key is a write
-- cost with no reader.
