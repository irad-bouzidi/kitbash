package dev.kitbash.core.recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * One selectable thing, as §4 defines it: a manifest plus a {@code files/} tree.
 *
 * <p>This record holds the manifest only. The files stay where they are until the plan stage asks
 * for them, so the whole catalog can sit in memory without also holding every template body.
 *
 * <p>{@code slot} is the option that offers this recipe as a choice — {@code backend}, {@code
 * database}, {@code docker}. It is null for a recipe nothing chooses directly: {@code base}
 * arrives because something else requires {@code project-root}, and offering it in the wizard
 * would be offering a decision nobody has.
 *
 * <p>Collections are stored sorted rather than in manifest order. Two recipes that declare the same
 * capabilities in a different order must resolve the same and hash the same, and the catalog digest
 * (§7) is computed over this data — sorting here means nothing downstream has to remember to.
 */
public record Recipe(
        RecipeId id,
        RecipeVersion version,
        String frameworkVersion,
        RecipeKind kind,
        String label,
        Set<Capability> provides,
        Set<Capability> requires,
        Set<RecipeId> conflictsWith,
        List<OptionSpec> options,
        Set<String> requiredVariables,
        List<FileRule> files,
        List<PatchRule> patches,
        boolean hasHook,
        String slot) {

    public Recipe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(kind, "kind");
        label = label == null || label.isBlank() ? id.value() : label;
        provides = sorted(provides);
        requires = sorted(requires);
        conflictsWith = sorted(conflictsWith);
        options = options == null ? List.of() : List.copyOf(options);
        requiredVariables = sorted(requiredVariables);
        files = files == null ? List.of() : List.copyOf(files);
        patches = patches == null ? List.of() : List.copyOf(patches);
        slot = slot == null || slot.isBlank() ? null : slot;
    }

    /**
     * The twelve-argument form, for a recipe nothing selects directly.
     *
     * <p>{@code base} is the case: nothing offers it as a choice, and it arrives because something
     * else requires {@code project-root}. A recipe with no slot can only be reached by implication,
     * which is exactly right for the ones that are not decisions.
     */
    public static Recipe implied(
            RecipeId id,
            RecipeVersion version,
            String frameworkVersion,
            RecipeKind kind,
            String label,
            Set<Capability> provides,
            Set<Capability> requires,
            Set<RecipeId> conflictsWith,
            List<OptionSpec> options,
            Set<String> requiredVariables,
            List<FileRule> files,
            List<PatchRule> patches,
            boolean hasHook) {
        return new Recipe(
                id,
                version,
                frameworkVersion,
                kind,
                label,
                provides,
                requires,
                conflictsWith,
                options,
                requiredVariables,
                files,
                patches,
                hasHook,
                null);
    }

    /** Whether some option offers this recipe as a choice, rather than it arriving by implication. */
    public boolean isSelectable() {
        return slot != null;
    }

    /** The options this recipe declares, keyed by id, in declaration order. */
    public Map<String, OptionSpec> optionsById() {
        return options.stream()
                .collect(Collectors.toMap(OptionSpec::id, spec -> spec, (first, second) -> first, LinkedHashMap::new));
    }

    public Optional<OptionSpec> option(String optionId) {
        return options.stream().filter(spec -> spec.id().equals(optionId)).findFirst();
    }

    public boolean provides(Capability capability) {
        return provides.contains(capability);
    }

    /** {@code backend-spring-java@1.4.0} — the form a pin and a lock entry both take (§7). */
    public String coordinate() {
        return id.value() + "@" + version;
    }

    private static <T extends Comparable<T>> Set<T> sorted(Set<T> values) {
        return values == null || values.isEmpty() ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(values));
    }
}
