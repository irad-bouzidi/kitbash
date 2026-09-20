package dev.kitbash.core.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * There is one schema version and nothing to migrate, which is exactly why this is written now:
 * once a preset row exists, adding the framework is a backfill instead of a commit (§7).
 */
class SelectionMigrationsTest {

    @Test
    @DisplayName("a current-version envelope passes through untouched")
    void currentVersionIsUnchanged() {
        SelectionEnvelope envelope = SelectionEnvelope.current("svc", Map.of("docker", true), Map.of());

        assertThat(SelectionMigrations.migrate(envelope)).isEqualTo(envelope);
    }

    @Test
    @DisplayName("an envelope from a newer server is refused with an upgrade hint, not half-read")
    void refusesFutureVersions() {
        SelectionEnvelope fromTheFuture = new SelectionEnvelope(99, "svc", Map.of(), Map.of());

        assertThatThrownBy(() -> SelectionMigrations.migrate(fromTheFuture))
                .isInstanceOf(SelectionValidationException.class)
                .hasMessageContaining("newer Kitbash")
                .hasMessageContaining("Upgrade the server");
    }

    @Test
    @DisplayName("a nonsensical version is refused rather than treated as version 1")
    void refusesVersionsBelowOne() {
        assertThatThrownBy(() -> SelectionMigrations.migrate(new SelectionEnvelope(0, "svc", Map.of(), Map.of())))
                .isInstanceOf(SelectionValidationException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    @DisplayName("the rename helper is the shape a real migration takes")
    void renameHelperMovesAnOptionKey() {
        SelectionEnvelope before = SelectionEnvelope.current("svc", Map.of("db", "db-postgres-flyway"), Map.of());

        SelectionEnvelope after =
                SelectionMigrations.renameOption("db", "database").apply(before);

        assertThat(after.options()).containsExactly(Map.entry("database", "db-postgres-flyway"));
    }

    @Test
    @DisplayName("parsing types the option values the wire could only carry loosely")
    void parsingTypesOptionValues() {
        Selection parsed = SelectionEnvelope.current(
                        "svc", Map.of("docker", true, "architecture", "layered", "javaVersion", 21), Map.of())
                .parse();

        assertThat(parsed.option("docker")).contains(OptionValue.flag(true));
        assertThat(parsed.option("architecture")).contains(OptionValue.text("layered"));
        // A number is coerced rather than rejected: it is the mistake every client makes once, and
        // the catalog type-checks the result against the option's declared type regardless.
        assertThat(parsed.option("javaVersion")).contains(OptionValue.text("21"));
    }
}
