package dev.kitbash.core.resolve;

import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.OptionType;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
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
 * catalog's *shape* — a base, two mutually exclusive backends, two build tools, a database, a
 * frontend, a feature, containers and CI — which is all the resolver reasons about.
 */
final class TestCatalog {

    private TestCatalog() {}

    static Catalog v1() {
        return Catalog.of(
                List.of(
                        recipe("base", RecipeKind.BASE, provides("project-root"), requires()),
                        recipe("build-gradle-kts", RecipeKind.BASE, provides("build-tool"), requires("project-root")),
                        recipe("build-maven", RecipeKind.BASE, provides("build-tool"), requires("project-root")),
                        backend("backend-spring-java", "backend-spring-kotlin"),
                        backend("backend-spring-kotlin", "backend-spring-java"),
                        recipe("db-postgres-flyway", RecipeKind.FEATURE, provides("database"), requires("jvm-project")),
                        frontend(),
                        recipe("feature-auth-jwt", RecipeKind.FEATURE, provides("auth"), requires("http-server")),
                        recipe(
                                "infra-docker",
                                RecipeKind.INFRA,
                                provides("docker", "containers"),
                                requires("project-root")),
                        recipe("ci-gitlab", RecipeKind.CI, provides("ci"), requires("project-root"))),
                "sha256:test");
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
                List.of(new OptionSpec(
                        "architecture",
                        OptionType.ENUM,
                        List.of("layered", "hexagonal"),
                        OptionValue.text("layered"),
                        "Architecture",
                        "Determines the package layout and dependency direction.")),
                Set.of("groupId", "packageName"),
                List.of(),
                List.of(),
                false);
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
                        "Generate a TypeScript client from the backend's OpenAPI document.")),
                Set.of(),
                List.of(),
                List.of(),
                false);
    }

    static Recipe recipe(String id, RecipeKind kind, Set<Capability> provides, Set<Capability> requires) {
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
                false);
    }

    static Set<Capability> provides(String... names) {
        return Arrays.stream(names).map(Capability::of).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Set<Capability> requires(String... names) {
        return provides(names);
    }
}
