package dev.kitbash.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every manifest points an editor at the schema (§43).
 *
 * <p>The line is one comment, and it is most of what "editor integration" means in practice:
 * completion, inline validation and hover documentation in anything speaking the YAML language
 * server protocol, against <b>the same schema the loader validates with</b> — so the editor and
 * the build agree by construction rather than by somebody keeping two copies in step.
 *
 * <p>A test rather than a convention because of which manifest would lack it. Not the twelve
 * written by people who knew: the first one written by somebody who did not, who then spends an
 * afternoon on a typo their editor would have underlined, and never learns the tooling existed.
 */
class SchemaLineTest {

    private static final String EXPECTED = "# yaml-language-server: $schema=";

    @Test
    @DisplayName("every recipe manifest carries the schema line, and it is the first line")
    void everyManifestPointsAtTheSchema() {
        List<String> missing = new ArrayList<>();
        List<Path> manifests = manifests();

        assertThat(manifests)
                .as("no manifests found; this test would pass by looking at nothing")
                .isNotEmpty();

        for (Path manifest : manifests) {
            String first = firstLine(manifest);
            if (!first.startsWith(EXPECTED)) {
                missing.add(
                        manifest.getParent().getFileName() + "/" + manifest.getFileName() + " starts '" + first + "'");
            }
        }

        assertThat(missing)
                .as(
                        "§43: a manifest without '%s…' is one whose author gets no completion and no "
                                + "inline validation, against the very schema the loader will reject them "
                                + "with. First line, so it is seen before it is needed.",
                        EXPECTED)
                .isEmpty();
    }

    /** Both manifests the catalog has: every recipe's, and the catalog's own. */
    private static List<Path> manifests() {
        Path recipes = locate();
        List<Path> manifests = new ArrayList<>();
        manifests.add(recipes.resolve("_catalog.yaml"));
        try (Stream<Path> directories = Files.list(recipes)) {
            directories
                    .filter(Files::isDirectory)
                    .map(directory -> directory.resolve("recipe.yaml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(manifests::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + recipes, e);
        }
        return manifests;
    }

    private static String firstLine(Path manifest) {
        try {
            return Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                    .findFirst()
                    .orElse("");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + manifest, e);
        }
    }

    private static Path locate() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("recipes"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "No recipes directory at or above " + Path.of("").toAbsolutePath());
        }
        return candidate.resolve("recipes");
    }
}
