package dev.kitbash.cli;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.core.selection.SelectionEnvelope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads a §7 selection envelope from a file, in exactly the shape the API takes as a body. */
final class Selections {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Selections() {}

    static SelectionEnvelope read(Path file) throws IOException {
        JsonNode document;
        try {
            document = JSON.readTree(Files.readAllBytes(file));
        } catch (JacksonException e) {
            throw new IllegalArgumentException(file + " is not valid JSON: " + e.getOriginalMessage(), e);
        }

        Map<String, Object> options = new LinkedHashMap<>();
        document.path("options")
                .propertyStream()
                .forEach(entry -> options.put(
                        entry.getKey(),
                        entry.getValue().isBoolean()
                                ? entry.getValue().asBoolean()
                                : entry.getValue().asText()));

        Map<String, String> variables = new LinkedHashMap<>();
        document.path("variables")
                .propertyStream()
                .forEach(entry -> variables.put(entry.getKey(), entry.getValue().asText()));

        return new SelectionEnvelope(
                document.path("schemaVersion").asInt(SelectionEnvelope.CURRENT_SCHEMA_VERSION),
                document.path("projectName").asText(""),
                options,
                variables);
    }
}
