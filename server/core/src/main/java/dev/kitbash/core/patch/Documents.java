package dev.kitbash.core.patch;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading and writing the two structured formats the appliers edit, with the output settings
 * pinned so a patched file looks like one a person would have written.
 *
 * <p>Two properties matter and neither is automatic. <b>Determinism</b>: key order is insertion
 * order throughout (never a {@code HashMap}), so re-running a generation produces the same bytes
 * and the zip cache key stays sound (§4). <b>Diff-friendliness</b>: §10 asks that formatting be
 * preserved where the parser allows, because a generator whose output nobody wants to read is a
 * generator nobody reviews — hence two-space JSON with npm's {@code "key": value} spacing rather
 * than Jackson's default {@code "key" : value}, and YAML without a document-start marker.
 *
 * <p>Comments do not survive a round trip through any tree model. That is recorded as a known
 * consequence in ADR 0003 rather than discovered later: explanatory prose belongs in a README
 * fragment, not in a {@code compose.yaml} some feature recipe is going to merge into.
 */
final class Documents {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code MINIMIZE_QUOTES} keeps the output readable; {@code ALWAYS_QUOTE_NUMBERS_AS_STRINGS}
     * is what makes that safe. Without it a version written as the string {@code "1.0"} comes back
     * out unquoted and the next reader resolves it as a float — the kind of silent corruption that
     * surfaces as an unbootable generated project rather than as an error here.
     */
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build());

    private static final MapType STRING_KEYED_MAP =
            JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class);

    private Documents() {}

    static Map<String, Object> readYaml(String source) {
        return read(YAML, source);
    }

    static Map<String, Object> readJson(String source) {
        return read(JSON, source);
    }

    static String writeYaml(Map<String, Object> document) {
        try {
            return YAML.writeValueAsString(document);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not write YAML", e);
        }
    }

    /** npm-style: two spaces, {@code "key": value}, and a trailing newline like every other tool. */
    static String writeJson(Map<String, Object> document) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(
                        Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
        printer.indentArraysWith(new DefaultIndenter("  ", "\n"));
        try {
            return JSON.writer(printer).writeValueAsString(document) + "\n";
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not write JSON", e);
        }
    }

    private static Map<String, Object> read(ObjectMapper mapper, String source) {
        if (source == null || source.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> document = mapper.readValue(source, STRING_KEYED_MAP);
            return document == null ? new LinkedHashMap<>() : document;
        } catch (JacksonException e) {
            throw new DocumentParseException(e.getOriginalMessage(), e);
        }
    }

    /**
     * Deep-merges {@code addition} into {@code base}.
     *
     * <p>Maps recurse and lists union. A scalar that is already set to a <i>different</i> value is
     * a collision and is refused through {@code onCollision} — §4 is explicit that this is a
     * feature: two recipes silently overwriting the same {@code application.yml} key is precisely
     * the bug class the typed operations exist to prevent. A scalar set to the <i>same</i> value is
     * a no-op, which is what makes every merge idempotent.
     */
    static void deepMerge(
            Map<String, Object> base, Map<String, Object> addition, String path, CollisionReporter onCollision) {
        addition.forEach((key, incoming) -> {
            String here = path.isEmpty() ? key : path + "." + key;
            Object existing = base.get(key);
            if (existing == null && !base.containsKey(key)) {
                base.put(key, incoming);
                return;
            }
            if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
                deepMerge(asMap(existingMap), asMap(incomingMap), here, onCollision);
                return;
            }
            if (existing instanceof List<?> existingList && incoming instanceof List<?> incomingList) {
                List<Object> merged = new ArrayList<>(existingList);
                incomingList.stream()
                        .filter(element -> !merged.contains(element))
                        .forEach(merged::add);
                base.put(key, merged);
                return;
            }
            if (!java.util.Objects.equals(existing, incoming)) {
                onCollision.report(here, existing, incoming);
            }
        });
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /** The nested map at {@code key}, created if absent — how {@code services} or {@code scripts} is reached. */
    static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map<?, ?> map) {
            return asMap(map);
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    @FunctionalInterface
    interface CollisionReporter {
        void report(String path, Object existing, Object incoming);
    }

    /** A target file that is not valid in its own format — a recipe bug, not a user's. */
    static final class DocumentParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DocumentParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
