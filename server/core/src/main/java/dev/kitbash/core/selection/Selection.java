package dev.kitbash.core.selection;

import dev.kitbash.core.hash.Sha256;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A selection after parsing: migrated to the current schema version, option values typed, ready for
 * the resolver (§6, stage 1).
 *
 * <p>Flat and keyed by option id, never nested by category. §7 is explicit about why: a nested
 * shape bakes the category taxonomy into every client, so adding a category becomes a breaking
 * change for all of them.
 *
 * <p>The canonical form and the hash computed from it live here rather than beside their first
 * consumer, because three separate subsystems key on them and they must agree: the zip cache (§10),
 * the verification dedupe key (§12) and generation dedupe in history (§10). A second, slightly
 * different canonicalisation somewhere else is a cache that returns the wrong project.
 */
public record Selection(String projectName, Map<String, OptionValue> options, Map<String, String> variables) {

    public Selection {
        Objects.requireNonNull(projectName, "projectName");
        options = options == null ? Map.of() : Map.copyOf(options);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static Selection of(String projectName, Map<String, String> variables) {
        return new Selection(projectName, Map.of(), variables);
    }

    public static Selection of(String projectName, Map<String, OptionValue> options, Map<String, String> variables) {
        return new Selection(projectName, options, variables);
    }

    public String variable(String name, String fallback) {
        String value = variables.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public Optional<OptionValue> option(String optionId) {
        return Optional.ofNullable(options.get(optionId));
    }

    /** The value of an enum or string option, or {@code fallback} when it was not set. */
    public String optionText(String optionId, String fallback) {
        return option(optionId)
                .filter(OptionValue.Text.class::isInstance)
                .map(value -> ((OptionValue.Text) value).value())
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }

    public boolean flag(String optionId, boolean fallback) {
        return option(optionId)
                .filter(OptionValue.Flag.class::isInstance)
                .map(value -> ((OptionValue.Flag) value).value())
                .orElse(fallback);
    }

    /** This selection with an option forced — how the resolver applies a catalog default. */
    public Selection with(String optionId, OptionValue value) {
        Map<String, OptionValue> merged = new LinkedHashMap<>(options);
        merged.put(optionId, value);
        return new Selection(projectName, merged, variables);
    }

    /**
     * The form everything hashes: keys sorted with an explicit comparator, blank variables dropped.
     *
     * <p>Sorting is explicit rather than incidental because {@code HashMap} iteration order is not
     * a contract, and a hash that changes when the JVM's hashing seed changes is a cache that
     * silently stops hitting.
     */
    public Selection canonical() {
        Map<String, OptionValue> canonicalOptions = new TreeMap<>(String::compareTo);
        options.forEach((key, value) -> {
            if (value != null) {
                canonicalOptions.put(key, value);
            }
        });
        Map<String, String> canonicalVariables = new TreeMap<>(String::compareTo);
        variables.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                canonicalVariables.put(key, value);
            }
        });
        return new Selection(
                projectName, new LinkedHashMap<>(canonicalOptions), new LinkedHashMap<>(canonicalVariables));
    }

    /**
     * The canonical form with every option left at its catalog default removed (§7).
     *
     * <p>Two users who reach the same stack — one by accepting the defaults, one by clicking every
     * control back to where it started — have made the same selection and must share a cache entry.
     */
    public Selection withDefaultsElided(Map<String, OptionValue> defaults) {
        Map<String, OptionValue> reduced = new LinkedHashMap<>();
        canonical().options.forEach((key, value) -> {
            if (!value.equals(defaults.get(key))) {
                reduced.put(key, value);
            }
        });
        return new Selection(projectName, reduced, canonical().variables);
    }

    /** Deterministic JSON, sorted, minimal whitespace: the exact bytes {@link #hash()} digests. */
    public String canonicalJson() {
        Selection canonical = canonical();
        StringBuilder out = new StringBuilder("{\"projectName\":")
                .append(Json.quote(canonical.projectName))
                .append(",\"options\":{");
        boolean first = true;
        for (Map.Entry<String, OptionValue> entry : canonical.options.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(Json.quote(entry.getKey()))
                    .append(':')
                    .append(entry.getValue().toJson());
        }
        out.append("},\"variables\":{");
        first = true;
        for (Map.Entry<String, String> entry : canonical.variables.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(Json.quote(entry.getKey())).append(':').append(Json.quote(entry.getValue()));
        }
        return out.append("}}").toString();
    }

    /** The {@code selectionHash} of §10 and §12: sha256 over {@link #canonicalJson()}. */
    public String hash() {
        return Sha256.ofUtf8(canonicalJson());
    }

    /** Back to the wire shape, at the current schema version. */
    /**
     * This selection as the §7 envelope a client would have sent.
     *
     * <p>The values are unwrapped back to the three shapes the wire has — a string, a boolean or a
     * list of strings — rather than left as {@link OptionValue}s. The parser accepts either, so in
     * memory it made no difference; serialised it made all of it, because a {@code Text} record
     * writes itself as {@code {"value":"…"}} and comes back as a map the parser refuses. Anything
     * that stores or sends an envelope needs this to be the wire shape, which is what the name
     * says it is.
     */
    public SelectionEnvelope toEnvelope() {
        Map<String, Object> wireOptions = new LinkedHashMap<>();
        options.forEach((key, value) -> wireOptions.put(key, wireValue(value)));
        return new SelectionEnvelope(SelectionEnvelope.CURRENT_SCHEMA_VERSION, projectName, wireOptions, variables);
    }

    private static Object wireValue(OptionValue value) {
        return switch (value) {
            case OptionValue.Text text -> text.value();
            case OptionValue.Flag flag -> flag.value();
            case OptionValue.Multi multi -> multi.values();
        };
    }
}
