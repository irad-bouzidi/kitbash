package dev.kitbash.catalog.metadata;

import java.util.List;

/**
 * The §8 metadata document: everything a client needs to render the wizard, and nothing it needs to
 * know beforehand.
 *
 * <p>This is the hinge of the whole design. §8: <i>the wizard renders itself from that document, so
 * adding a recipe is a backend-only change. Resist every temptation to hardcode an option name in
 * React.</i> Every human-readable string here comes from the catalog, every option carries its own
 * type from a closed set, and the client switches on type and nothing else.
 *
 * <p>It is immutable per catalog digest, which is what makes the ETag on it correct rather than
 * hopeful: two responses with the same digest are the same bytes, and a client that already has
 * that digest has the whole catalog.
 */
public record MetadataDocument(
        int schemaVersion,
        String catalogDigest,
        int recipeCount,
        List<Group> groups,
        List<Variable> variables,
        List<RecipeSummary> recipes) {

    /** The current shape of this document. Bumped when a client would have to change to read it. */
    public static final int SCHEMA_VERSION = 1;

    public MetadataDocument {
        groups = List.copyOf(groups);
        variables = List.copyOf(variables);
        recipes = List.copyOf(recipes);
    }

    /** A titled section of the wizard, in display order. */
    public record Group(String id, String label, String help, int order, List<Option> options) {
        public Group {
            options = List.copyOf(options);
        }
    }

    /**
     * One control.
     *
     * <p>{@code availableWhen} names every recipe that declares this option, and is empty for a
     * slot. §9 wants an option that does not currently apply to stay <b>visible and disabled</b>
     * with the reason on hover — hiding it makes the catalog feel arbitrary — and this is what
     * lets a client do that without knowing what any of the options mean.
     *
     * <p>A list rather than one recipe id since §30, because {@code architecture} is declared by
     * both JVM backends. Emitting one control per declaring recipe put two identical
     * <i>Architecture</i> dropdowns in the wizard, one of them permanently disabled, and gave them
     * the same id — so a client keying controls by id had two with the same key. One control whose
     * availability is "any of these recipes" is the shape the UI actually needs.
     */
    public record Option(
            String id,
            String type,
            String label,
            String help,
            boolean required,
            Object defaultValue,
            List<String> availableWhen,
            List<Choice> choices) {
        public Option {
            availableWhen = List.copyOf(availableWhen);
            choices = List.copyOf(choices);
        }
    }

    /**
     * One value an option can take.
     *
     * <p>{@code verification} was reserved by {@code kitbash-15} for the badge §12 feeds, and it is
     * still null — because {@code kitbash-38} found the reservation was for the wrong shape, and
     * left it rather than filling it with something misleading.
     *
     * <p>Two things settle it. Verification is a property of <b>combinations</b>, not of choices:
     * Kotlin was not red in §36, Kotlin <i>with the typed client</i> was, while Kotlin with
     * everything else stayed green — and a per-choice field could only have said "Kotlin: failed",
     * which is alarming, unactionable and untrue. And this document is immutable per catalog
     * digest, which is what makes its ETag honest; verification results change <i>within</i> a
     * digest, because the nightly runs later than the deploy, so carrying them here would quietly
     * turn a correct cache into a stale one.
     *
     * <p>Badges are therefore {@code GET /api/v1/verification}, with an entity tag over the catalog
     * digest and the run that produced them. The field stays for now because removing it from a
     * published document is a client-visible change worth making on purpose rather than in passing.
     */
    public record Choice(
            String value,
            String label,
            String recipeId,
            String recipeVersion,
            String frameworkVersion,
            List<String> provides,
            List<String> requires,
            List<String> conflictsWith,
            String verification) {
        public Choice {
            provides = List.copyOf(provides);
            requires = List.copyOf(requires);
            conflictsWith = List.copyOf(conflictsWith);
        }
    }

    /** A free-text input, with the rule the server will check it against anyway (§13). */
    public record Variable(
            String id,
            String label,
            String help,
            String pattern,
            String defaultValue,
            String scope,
            List<String> requiredBy) {
        public Variable {
            requiredBy = List.copyOf(requiredBy);
        }
    }

    /** The catalog as a flat list, for the read-only Catalog page §9 describes. */
    public record RecipeSummary(
            String id,
            String label,
            String kind,
            String slot,
            String recipeVersion,
            String frameworkVersion,
            List<String> provides,
            List<String> requires,
            List<String> conflictsWith) {
        public RecipeSummary {
            provides = List.copyOf(provides);
            requires = List.copyOf(requires);
            conflictsWith = List.copyOf(conflictsWith);
        }
    }
}
