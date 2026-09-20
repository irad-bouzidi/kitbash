package dev.kitbash.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The first of the two validation layers §7 asks for: the manifest is checked against
 * {@code /recipes/_schema/recipe.schema.json} before a single field is read.
 *
 * <p>Two layers keep the messages specific. The schema catches shape — a misspelled key, a patch op
 * that does not exist, an enum option with no values — and says exactly where; the semantic pass in
 * {@link CatalogLoader} then catches what a schema cannot express, such as a {@code when} naming an
 * option nobody declared. Doing it in one hand-written pass produces either vague errors or a
 * thousand lines of field checking.
 */
final class ManifestSchema {

    private static final String RESOURCE = "/kitbash/schema/recipe.schema.json";

    private final Schema schema;

    private ManifestSchema(Schema schema) {
        this.schema = schema;
    }

    static ManifestSchema load() {
        try (InputStream source = ManifestSchema.class.getResourceAsStream(RESOURCE)) {
            if (source == null) {
                throw new IllegalStateException("The recipe manifest schema is missing from the classpath at "
                        + RESOURCE + ". It is staged there from /recipes/_schema by the catalog module's build.");
            }
            return new ManifestSchema(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(source));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the recipe manifest schema from " + RESOURCE, e);
        }
    }

    /** Throws with every violation listed, sorted by location, rather than only the first. */
    void validate(String file, JsonNode manifest) {
        List<Error> errors = schema.validate(manifest);
        if (errors.isEmpty()) {
            return;
        }
        String detail = errors.stream()
                .sorted(Comparator.comparing(
                        error -> error.getInstanceLocation().toString()))
                .map(error -> "    " + error.getInstanceLocation() + " " + error.getMessage())
                .distinct()
                .collect(Collectors.joining(System.lineSeparator()));
        throw new RecipeLoadException(
                file,
                null,
                "does not match the recipe manifest schema:" + System.lineSeparator() + detail,
                "See recipes/_schema/recipe.schema.json and docs/recipe-format.md for the field list.");
    }
}
