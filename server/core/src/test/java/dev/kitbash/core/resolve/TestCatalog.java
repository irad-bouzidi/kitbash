package dev.kitbash.core.resolve;

import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.OptionGroup;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.OptionType;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import dev.kitbash.core.recipe.Slot;
import dev.kitbash.core.recipe.SlotType;
import dev.kitbash.core.selection.OptionValue;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A stand-in for the v1 catalog of §11, built in memory.
 *
 * <p>Resolver tests must not read a recipe tree: §8 requires the suite to be pure and fast enough
 * to run on every save, and a test that touches the filesystem is neither. This mirrors the real
 * catalog's <i>shape</i> — a base, two mutually exclusive backends, two build tools, a database, a
 * frontend, a feature, containers and CI, each in a declared slot — which is all the resolver
 * reasons about.
 */
final class TestCatalog {

    private TestCatalog() {}

    static Catalog v1() {
        return Catalog.of(
                List.of(
                        recipe("base", RecipeKind.BASE, provides("project-root"), requires(), null),
                        recipe(
                                "build-gradle-kts",
                                RecipeKind.BASE,
                                provides("build-tool"),
                                requires("project-root"),
                                "buildTool"),
                        recipe(
                                "build-maven",
                                RecipeKind.BASE,
                                provides("build-tool"),
                                requires("project-root"),
                                "buildTool"),
                        backend("backend-spring-java", "backend-spring-kotlin"),
                        backend("backend-spring-kotlin", "backend-spring-java"),
                        recipe(
                                "db-postgres-flyway",
                                RecipeKind.FEATURE,
                                provides("database"),
                                requires("jvm-project"),
                                "database"),
                        frontend(),
                        mobile(),
                        recipe(
                                "feature-auth-jwt",
                                RecipeKind.FEATURE,
                                provides("auth"),
                                requires("http-server"),
                                "auth"),
                        recipe(
                                "infra-docker",
                                RecipeKind.INFRA,
                                provides("docker", "containers"),
                                requires("project-root"),
                                "docker"),
                        recipe("ci-gitlab", RecipeKind.CI, provides("ci"), requires("project-root"), "ci")),
                "sha256:test",
                groups(),
                List.of());
    }

    /** The slots this catalog offers, in one group — enough for the resolver to read. */
    static List<OptionGroup> groups() {
        return List.of(new OptionGroup(
                "stack",
                "Stack",
                "What the project is made of.",
                1,
                List.of(
                        slot("backend", SlotType.ENUM),
                        slot("frontend", SlotType.ENUM),
                        slot("mobile", SlotType.ENUM),
                        slot("buildTool", SlotType.ENUM),
                        slot("database", SlotType.ENUM),
                        slot("auth", SlotType.ENUM),
                        slot("ci", SlotType.ENUM),
                        slot("docker", SlotType.BOOLEAN))));
    }

    static Slot slot(String id, SlotType type) {
        return new Slot(id, type, id, "help for " + id, false, false, "stack");
    }

    private static Recipe backend(String id, String conflictsWith) {
        return new Recipe(
                RecipeId.of(id),
                RecipeVersion.parse("1.0.0"),
                "3.5.5",
                RecipeKind.BACKEND,
                id,
                provides("http-server", "rest-api", "openapi-spec", "jvm-project"),
                provides("project-root", "build-tool", "database"),
                Set.of(RecipeId.of(conflictsWith)),
                List.of(OptionSpec.of(
                        "architecture",
                        OptionType.ENUM,
                        List.of("layered", "hexagonal"),
                        OptionValue.text("layered"),
                        "Architecture",
                        "Determines the package layout and dependency direction.")),
                Set.of("groupId", "packageName"),
                List.of(),
                List.of(),
                false,
                "backend");
    }

    private static Recipe frontend() {
        return new Recipe(
                RecipeId.of("frontend-react-vite"),
                RecipeVersion.parse("1.0.0"),
                "19.0.0",
                RecipeKind.FRONTEND,
                "React + Vite",
                provides("spa"),
                provides("project-root"),
                Set.of(),
                List.of(new OptionSpec(
                        "typedClient",
                        OptionType.BOOLEAN,
                        List.of(),
                        OptionValue.flag(false),
                        "Typed API client",
                        "Generate a TypeScript client from the backend's OpenAPI document.",
                        Capability.of("openapi-spec"))),
                Set.of(),
                List.of(),
                List.of(),
                false,
                "frontend");
    }

    /**
     * Conflicts worth testing are cross-slot ones. Two recipes in the <i>same</i> slot exclude each
     * other structurally — a slot holds one — so a {@code conflictsWith} between them is dead
     * configuration, and the loader refuses it.
     */
    private static Recipe mobile() {
        return new Recipe(
                RecipeId.of("mobile-expo"),
                RecipeVersion.parse("1.0.0"),
                null,
                RecipeKind.MOBILE,
                "React Native (Expo)",
                provides("mobile-app"),
                provides("project-root"),
                Set.of(RecipeId.of("frontend-react-vite")),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                false,
                "mobile");
    }

    static Recipe recipe(String id, RecipeKind kind, Set<Capability> provides, Set<Capability> requires, String slot) {
        return new Recipe(
                RecipeId.of(id),
                RecipeVersion.parse("1.0.0"),
                null,
                kind,
                id,
                provides,
                requires,
                Set.of(),
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                false,
                slot);
    }

    static Set<Capability> provides(String... names) {
        return Arrays.stream(names).map(Capability::of).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Set<Capability> requires(String... names) {
        return provides(names);
    }
}
