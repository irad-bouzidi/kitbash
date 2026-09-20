package dev.kitbash.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §10 decides that the recipe catalog is <b>not</b> in the database, and that decision is easy to
 * erode one convenient table at a time.
 *
 * <p>The Implementation Plan's {@code Technology} / {@code TechnologyVersion} / {@code
 * Architecture} tables would put every recipe change behind a migration and an admin CRUD screen
 * for no gain — a DB-backed catalog is only needed for user-uploaded recipes, which is a phase 5
 * question with real security weight (§13, kitbash-47). This test has nothing to find today; it
 * exists so the day somebody adds such a migration is the day CI says so.
 */
class CatalogIsNotInTheDatabaseTest {

    private static final List<String> FORBIDDEN_SUBJECTS =
            List.of("recipe", "technology", "technologies", "architecture", "capability");

    @Test
    @DisplayName("no database migration describes recipes, technologies or architectures")
    void noMigrationDescribesTheCatalog() {
        Path repository = repositoryRoot();

        List<Path> migrations = walk(repository)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().replace('\\', '/').contains("/db/migration/"))
                .toList();

        assertThat(migrations).allSatisfy(migration -> {
            String body = read(migration).toLowerCase(Locale.ROOT);
            assertThat(FORBIDDEN_SUBJECTS)
                    .withFailMessage(
                            "%s appears to describe the recipe catalog. §10 keeps the catalog in git, "
                                    + "not in Postgres: recipes stay reviewable, diffable and versioned with "
                                    + "the code that renders them.",
                            migration)
                    .noneSatisfy(subject -> assertThat(body).contains("table " + subject));
        });
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        assertThat(candidate)
                .as("repository root (the directory holding /recipes)")
                .isNotNull();
        return candidate;
    }

    private static Stream<Path> walk(Path root) {
        try {
            return Files.walk(root)
                    .filter(path -> !path.toString().contains("/node_modules/"))
                    .filter(path -> !path.toString().contains("/build/"))
                    .filter(path -> !path.toString().contains("/.git/"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
