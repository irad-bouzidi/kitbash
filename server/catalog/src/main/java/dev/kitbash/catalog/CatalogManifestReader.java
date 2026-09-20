package dev.kitbash.catalog;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.kitbash.core.recipe.OptionGroup;
import dev.kitbash.core.recipe.Slot;
import dev.kitbash.core.recipe.SlotType;
import dev.kitbash.core.recipe.VariableSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads {@code /recipes/_catalog.yaml}: the wizard's sections, the slots recipes fill, and the
 * free-text inputs.
 *
 * <p>It is a separate file from the recipes rather than something derived from them because it
 * describes what the catalog <i>offers</i>, which is not the same as what it contains. A slot with
 * no recipe in it yet is a coherent thing to declare — {@code mobile} will be one for a while — and
 * a label and a display order belong to the catalog, not to whichever recipe happened to be added
 * first.
 */
final class CatalogManifestReader {

    static final String FILE = "_catalog.yaml";

    private static final String SCHEMA_RESOURCE = "/kitbash/schema/catalog.schema.json";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Schema schema;

    CatalogManifestReader() {
        try (InputStream source = CatalogManifestReader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (source == null) {
                throw new IllegalStateException("The catalog schema is missing from the classpath at " + SCHEMA_RESOURCE
                        + ". It is staged there from /recipes/_schema by the build.");
            }
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the catalog schema", e);
        }
    }

    /** What a catalog manifest holds, once read. */
    record CatalogManifest(List<OptionGroup> groups, List<VariableSpec> variables) {

        static CatalogManifest empty() {
            return new CatalogManifest(List.of(), List.of());
        }
    }

    CatalogManifest read(Path root) {
        Path file = root.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            // A tree with no _catalog.yaml still loads: the CLI's `--catalog` points at recipe
            // directories under development, and a metadata document is not what that is for.
            return CatalogManifest.empty();
        }
        JsonNode manifest = parse(file);
        validate(manifest);

        List<VariableSpec> variables = new ArrayList<>();
        for (JsonNode node : manifest.path("variables")) {
            variables.add(readVariable(node));
        }

        List<OptionGroup> groups = new ArrayList<>();
        for (JsonNode node : manifest.path("groups")) {
            groups.add(readGroup(node));
        }
        groups.sort(Comparator.comparingInt(OptionGroup::order).thenComparing(OptionGroup::id));
        return new CatalogManifest(List.copyOf(groups), List.copyOf(variables));
    }

    private static VariableSpec readVariable(JsonNode node) {
        String id = node.path("id").asText();
        try {
            return new VariableSpec(
                    id,
                    node.path("label").asText(null),
                    node.path("help").asText(null),
                    node.path("pattern").asText(null),
                    node.path("default").asText(null));
        } catch (IllegalArgumentException e) {
            throw new RecipeLoadException(
                    FILE, "variables[" + id + "]", e.getMessage(), "See docs/recipe-format.md.", e);
        }
    }

    private static OptionGroup readGroup(JsonNode node) {
        String groupId = node.path("id").asText();
        List<Slot> slots = new ArrayList<>();
        for (JsonNode slot : node.path("slots")) {
            slots.add(readSlot(slot, groupId));
        }
        return new OptionGroup(
                groupId,
                node.path("label").asText(null),
                node.path("help").asText(null),
                node.path("order").asInt(),
                slots);
    }

    private static Slot readSlot(JsonNode node, String groupId) {
        String slotId = node.path("id").asText();
        SlotType type = SlotType.fromWireName(node.path("type").asText())
                .orElseThrow(() -> new RecipeLoadException(
                        FILE,
                        "groups[" + groupId + "].slots[" + slotId + "].type",
                        "'" + node.path("type").asText() + "' is not a slot type.",
                        "Use `enum` for a choice between recipes, or `boolean` for a toggle."));
        try {
            return new Slot(
                    slotId,
                    type,
                    node.path("label").asText(null),
                    node.path("help").asText(null),
                    node.path("required").asBoolean(false),
                    node.path("defaultOn").asBoolean(false),
                    groupId);
        } catch (IllegalArgumentException e) {
            throw new RecipeLoadException(
                    FILE, "groups[" + groupId + "].slots[" + slotId + "]", e.getMessage(), "See §8.", e);
        }
    }

    private JsonNode parse(Path file) {
        try {
            return YAML.readTree(Files.readAllBytes(file));
        } catch (JacksonException e) {
            throw new RecipeLoadException(
                    FILE, null, "is not readable as YAML: " + e.getOriginalMessage(), "Fix the YAML syntax.", e);
        } catch (IOException e) {
            throw new RecipeLoadException(FILE, null, "could not be read.", "Check file permissions.", e);
        }
    }

    private void validate(JsonNode manifest) {
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
                FILE,
                null,
                "does not match the catalog schema:" + System.lineSeparator() + detail,
                "See recipes/_schema/catalog.schema.json and docs/recipe-format.md.");
    }
}
