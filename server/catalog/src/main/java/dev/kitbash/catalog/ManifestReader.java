package dev.kitbash.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.FileRule;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.OptionType;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import dev.kitbash.core.selection.OptionValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One {@code recipe.yaml} → one {@link Recipe}.
 *
 * <p>The schema has already run by the time anything here executes, so this reads fields it knows
 * are shaped correctly and converts them into the domain types. What it still checks itself is
 * everything the schema cannot: that a version parses as semver, that a kind is one the enum knows,
 * and that an option's declared default is a value that option can actually hold.
 */
final class ManifestReader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final ManifestSchema schema;

    ManifestReader(ManifestSchema schema) {
        this.schema = schema;
    }

    Recipe read(Path manifestPath, String displayPath) {
        JsonNode manifest = parse(manifestPath, displayPath);
        schema.validate(displayPath, manifest);

        RecipeId id = readId(manifest, displayPath);
        RecipeVersion version = readVersion(manifest, displayPath);
        RecipeKind kind = readKind(manifest, displayPath);
        List<OptionSpec> options = readOptions(manifest, displayPath);

        return new Recipe(
                id,
                version,
                text(manifest, "frameworkVersion", null),
                kind,
                text(manifest, "label", id.value()),
                capabilities(manifest, "provides", displayPath),
                capabilities(manifest, "requires", displayPath),
                recipeIds(manifest, "conflictsWith", displayPath),
                options,
                stringSet(manifest.path("variables").path("required")),
                readFiles(manifest),
                readPatches(manifest, id, displayPath),
                manifest.path("hook").asBoolean(false),
                text(manifest, "slot", null));
    }

    private JsonNode parse(Path manifestPath, String displayPath) {
        try {
            return YAML.readTree(Files.readAllBytes(manifestPath));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // Jackson's own message already carries the line and column, which is the only part of
            // a parse failure anybody acts on.
            throw new RecipeLoadException(
                    displayPath, null, "is not readable as YAML: " + e.getOriginalMessage(), "Fix the YAML syntax.", e);
        } catch (IOException e) {
            throw new RecipeLoadException(displayPath, null, "could not be read.", "Check file permissions.", e);
        }
    }

    private static RecipeId readId(JsonNode manifest, String displayPath) {
        String value = text(manifest, "id", null);
        try {
            return RecipeId.of(value);
        } catch (IllegalArgumentException e) {
            throw new RecipeLoadException(displayPath, "id", e.getMessage(), "Use lowercase kebab-case.", e);
        }
    }

    private static RecipeVersion readVersion(JsonNode manifest, String displayPath) {
        String value = text(manifest, "version", null);
        try {
            return RecipeVersion.parse(value);
        } catch (IllegalArgumentException e) {
            throw new RecipeLoadException(
                    displayPath,
                    "version",
                    e.getMessage(),
                    "Recipe versions are plain MAJOR.MINOR.PATCH semver, independent of the framework "
                            + "version — put the framework's version in frameworkVersion instead.",
                    e);
        }
    }

    private static RecipeKind readKind(JsonNode manifest, String displayPath) {
        String value = text(manifest, "kind", null);
        return RecipeKind.fromWireName(value)
                .orElseThrow(() -> new RecipeLoadException(
                        displayPath,
                        "kind",
                        "'" + value + "' is not a recipe kind.",
                        "Use one of: base, backend, frontend, mobile, feature, infra, ci."));
    }

    private static List<OptionSpec> readOptions(JsonNode manifest, String displayPath) {
        List<OptionSpec> options = new ArrayList<>();
        for (JsonNode node : manifest.path("options")) {
            String optionId = node.path("id").asText();
            OptionType type = OptionType.fromWireName(node.path("type").asText())
                    .orElseThrow(() -> new RecipeLoadException(
                            displayPath,
                            "options[" + optionId + "].type",
                            "'" + node.path("type").asText() + "' is not an option type.",
                            "Use one of: enum, boolean, string, multi-select. The wizard switches on "
                                    + "type, never on option id (§9), so the set is closed."));
            List<String> values = stringList(node.path("values"));
            OptionValue defaultValue = readDefault(node, type, values, optionId, displayPath);
            String demands = node.path("demands").asText(null);
            OptionSpec spec;
            try {
                spec = new OptionSpec(
                        optionId,
                        type,
                        values,
                        defaultValue,
                        node.path("label").asText(null),
                        node.path("help").asText(null),
                        demands == null || demands.isBlank() ? null : Capability.of(demands));
            } catch (IllegalArgumentException e) {
                throw new RecipeLoadException(displayPath, "options[" + optionId + "]", e.getMessage(), "See §9.", e);
            }
            if (!spec.accepts(defaultValue)) {
                throw new RecipeLoadException(
                        displayPath,
                        "options[" + optionId + "].default",
                        "the declared default is not a value this option can hold.",
                        "Pick one of: " + String.join(", ", values));
            }
            options.add(spec);
        }
        return options;
    }

    private static OptionValue readDefault(
            JsonNode node, OptionType type, List<String> values, String optionId, String displayPath) {
        JsonNode declared = node.path("default");
        if (declared.isMissingNode() || declared.isNull()) {
            return switch (type) {
                case BOOLEAN -> OptionValue.flag(false);
                case MULTI_SELECT -> OptionValue.multi(List.of());
                case STRING -> OptionValue.text("");
                case ENUM ->
                    values.isEmpty()
                            ? OptionValue.text("")
                            // An enum with no declared default takes its first value, which is why
                            // manifest order of `values` is meaningful and worth reviewing.
                            : OptionValue.text(values.get(0));
            };
        }
        if (declared.isBoolean()) {
            return OptionValue.flag(declared.asBoolean());
        }
        if (declared.isArray()) {
            return OptionValue.multi(stringList(declared));
        }
        if (declared.isTextual()) {
            return OptionValue.text(declared.asText());
        }
        throw new RecipeLoadException(
                displayPath,
                "options[" + optionId + "].default",
                "must be a string, a boolean or an array.",
                "Quote the value if it is meant to be a string.");
    }

    private static List<FileRule> readFiles(JsonNode manifest) {
        List<FileRule> rules = new ArrayList<>();
        for (JsonNode node : manifest.path("files")) {
            rules.add(new FileRule(node.path("from").asText(), node.path("when").asText(null)));
        }
        return rules;
    }

    private static List<PatchRule> readPatches(JsonNode manifest, RecipeId owner, String displayPath) {
        List<PatchRule> rules = new ArrayList<>();
        int index = 0;
        for (JsonNode node : manifest.path("patches")) {
            String field = "patches[" + index++ + "]";
            rules.add(new PatchRule(
                    readPatchOp(node, owner, field, displayPath),
                    node.path("when").asText(null)));
        }
        return rules;
    }

    private static PatchOp readPatchOp(JsonNode node, RecipeId owner, String field, String displayPath) {
        String operation = node.path("op").asText();
        String target = node.path("target").asText();
        return switch (operation) {
            case "addDependency" ->
                new PatchOp.AddDependency(
                        owner,
                        target,
                        node.path("configuration").asText(),
                        node.path("coordinate").asText(),
                        node.path("versionRef").asText(null));
            case "mergeYaml" -> new PatchOp.MergeYaml(owner, target, objectMap(node.path("content")));
            case "mergeJson" -> new PatchOp.MergeJson(owner, target, objectMap(node.path("content")));
            case "addScript" ->
                new PatchOp.AddScript(
                        owner,
                        target,
                        node.path("name").asText(),
                        node.path("command").asText());
            case "insertAtMarker" ->
                new PatchOp.InsertAtMarker(owner, target, node.path("marker").asText(), stringList(node.path("lines")));
            case "appendLines" -> new PatchOp.AppendLines(owner, target, stringList(node.path("lines")));
            case "addEnvVar" ->
                new PatchOp.AddEnvVar(
                        owner,
                        target,
                        node.path("composeTarget").asText(null),
                        node.path("composeService").asText(null),
                        node.path("name").asText(),
                        node.path("value").asText(),
                        node.path("composeValue").asText(null),
                        node.path("comment").asText(null));
            case "addComposeService" ->
                new PatchOp.AddComposeService(
                        owner,
                        target,
                        node.path("service").asText(),
                        objectMap(node.path("definition")),
                        stringList(node.path("dependsOn")));
            default ->
                throw new RecipeLoadException(
                        displayPath,
                        field + ".op",
                        "'" + operation + "' is not a patch operation.",
                        "The eight §4 operations are addDependency, mergeYaml, mergeJson, addScript, "
                                + "insertAtMarker, appendLines, addEnvVar, addComposeService. The set is "
                                + "closed on purpose: a ninth is a design discussion, not a manifest change.");
        };
    }

    private static Set<Capability> capabilities(JsonNode manifest, String field, String displayPath) {
        Set<Capability> capabilities = new LinkedHashSet<>();
        for (String name : stringList(manifest.path(field))) {
            try {
                capabilities.add(Capability.of(name));
            } catch (IllegalArgumentException e) {
                throw new RecipeLoadException(displayPath, field, e.getMessage(), "Use lowercase kebab-case.", e);
            }
        }
        return capabilities;
    }

    private static Set<RecipeId> recipeIds(JsonNode manifest, String field, String displayPath) {
        Set<RecipeId> ids = new LinkedHashSet<>();
        for (String value : stringList(manifest.path(field))) {
            try {
                ids.add(RecipeId.of(value));
            } catch (IllegalArgumentException e) {
                throw new RecipeLoadException(displayPath, field, e.getMessage(), "Use lowercase kebab-case.", e);
            }
        }
        return ids;
    }

    private static Map<String, Object> objectMap(JsonNode node) {
        return node.isObject()
                ? YAML.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {})
                : Map.of();
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(element -> values.add(element.asText()));
        return values;
    }

    private static Set<String> stringSet(JsonNode node) {
        return new LinkedHashSet<>(stringList(node));
    }

    private static String text(JsonNode manifest, String field, String fallback) {
        JsonNode node = manifest.path(field);
        return node.isMissingNode() || node.isNull() ? fallback : node.asText();
    }
}
