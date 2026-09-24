package dev.kitbash.api.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the migrations actually built (§10).
 *
 * <p>The snapshot is the point. A migration that is reviewed once and then only ever added to is a
 * schema nobody can see whole, and §10 specifies this one field for field — so the shape the
 * database ends up with is checked against a file a reviewer can read in a diff, rather than
 * against a mental model of eleven migrations applied in order.
 *
 * <p>Regenerate deliberately, never as a side effect:
 * {@code ./gradlew :api:test -Dkitbash.schema.update=true}
 */
class SchemaTest {

    private static final Path SNAPSHOT =
            Path.of("src/test/resources/schema.snapshot.txt").toAbsolutePath();

    @Test
    @DisplayName("the schema matches the checked-in snapshot, column for column")
    void matchesTheSnapshot() {
        String actual = describeSchema();

        if (Boolean.getBoolean("kitbash.schema.update")) {
            write(SNAPSHOT, actual);
            return;
        }

        assertThat(actual)
                .as("The schema changed. Read the diff: if it is what the migration intended, "
                        + "regenerate with -Dkitbash.schema.update=true and review the snapshot in the "
                        + "same commit.")
                .isEqualTo(read(SNAPSHOT));
    }

    /**
     * §10 rejects {@code Technology} / {@code TechnologyVersion} / {@code Architecture} outright,
     * and this is the assertion that keeps that decision from eroding one convenient table at a
     * time. The catalog lives in git: reviewable, diffable, versioned with the code that renders
     * it. A database-backed catalog is a phase 5 question with real security weight (kitbash-47).
     *
     * <p>kitbash-47 asked that question and the answer was to build the feature, so this
     * assertion has changed rather than held. What it guards now is narrower and still worth
     * guarding: the two tables that feature introduced are named here <b>exactly</b>, so a third
     * table describing a recipe still has to argue for itself in a diff somebody reads.
     *
     * <p>{@code contributed_recipe} is the one that crosses the line, and it crosses it in a
     * particular shape — a submission somebody made and a reviewer approved, with both people
     * recorded — rather than as a configuration screen for what the generator supports. Shipped
     * recipes are still in git and still are not here. See
     * {@code docs/adr/0004-contributed-recipes-accepted.md}.
     */
    @Test
    @DisplayName("no table describes the catalog, because the catalog is not in the database")
    void theCatalogIsNotInTheDatabase() {
        List<String> tables = PostgresFixture.jdbc()
                .sql(
                        """
                        select table_name from information_schema.tables
                        where table_schema = 'public' and table_type = 'BASE TABLE'
                        order by table_name
                        """)
                .query(String.class)
                .list();

        assertThat(tables)
                .as("the §10 tables, Flyway's bookkeeping, and kitbash-47's two")
                .containsExactly(
                        "contributed_recipe",
                        "flyway_schema_history",
                        "generation",
                        "generation_flag",
                        "preset",
                        "share_link",
                        "verification_run");

        assertThat(tables)
                .as("§10 rejected these three by name, and building kitbash-47 did not revive them: "
                        + "a contributed recipe is a submission with a reviewer attached, not a catalog")
                .noneMatch(table ->
                        table.contains("technology") || table.contains("architecture") || table.contains("catalog"));
    }

    @Test
    @DisplayName("migrating an already-migrated database does nothing, rather than failing")
    void isIdempotent() {
        int before = appliedMigrations();

        PostgresFixture.migrate();
        PostgresFixture.migrate();

        assertThat(appliedMigrations())
                .as("re-running Flyway applied something a second time")
                .isEqualTo(before);
    }

    private static int appliedMigrations() {
        return PostgresFixture.jdbc()
                .sql("select count(*) from flyway_schema_history where success")
                .query(Integer.class)
                .single();
    }

    /**
     * Columns, types, nullability and defaults, plus indexes — everything a reader would want to
     * check §10 against, and nothing that varies between machines.
     */
    private static String describeSchema() {
        StringBuilder out = new StringBuilder();

        List<String> columns = PostgresFixture.jdbc()
                .sql(
                        """
                        select table_name || '.' || column_name || ' : ' || data_type
                               || case when is_nullable = 'NO' then ' not null' else '' end
                               || coalesce(' default ' || column_default, '')
                        from information_schema.columns
                        where table_schema = 'public' and table_name <> 'flyway_schema_history'
                        order by table_name, ordinal_position
                        """)
                .query(String.class)
                .list();
        out.append("columns\n");
        columns.forEach(column -> out.append("  ").append(column).append('\n'));

        List<String> indexes = PostgresFixture.jdbc()
                .sql(
                        """
                        select indexdef from pg_indexes
                        where schemaname = 'public' and tablename <> 'flyway_schema_history'
                        order by indexname
                        """)
                .query(String.class)
                .list();
        out.append("\nindexes and constraints\n");
        indexes.forEach(index -> out.append("  ").append(index).append('\n'));

        List<String> foreignKeys = PostgresFixture.jdbc()
                .sql(
                        """
                        select conrelid::regclass || ' -> ' || confrelid::regclass
                               || ' on delete ' || case confdeltype
                                    when 'a' then 'no action' when 'r' then 'restrict'
                                    when 'c' then 'cascade' when 'n' then 'set null'
                                    when 'd' then 'set default' else confdeltype::text end
                        from pg_constraint where contype = 'f'
                        order by 1
                        """)
                .query(String.class)
                .list();
        out.append("\nforeign keys\n");
        foreignKeys.forEach(key -> out.append("  ").append(key).append('\n'));

        return out.toString();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "No schema snapshot at " + path + ". Create it with -Dkitbash.schema.update=true.", e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + path, e);
        }
    }
}
