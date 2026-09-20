package dev.kitbash.core.hook;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.FileRule;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import java.util.List;
import java.util.Set;

/** Three recipes shaped like the real ones, enough for a hook to have something to aggregate. */
final class TestRecipes {

    private TestRecipes() {}

    static Recipe base() {
        return recipe("base", RecipeKind.BASE, Set.of(Capability.of("project-root")), List.of(), false);
    }

    static Recipe gradle() {
        return recipe(
                "build-gradle-kts",
                RecipeKind.BASE,
                Set.of(Capability.of("build-tool")),
                List.of(PatchRule.always(new PatchOp.AddDependency(
                        RecipeId.of("build-gradle-kts"),
                        "build.gradle.kts",
                        "testImplementation",
                        "org.assertj:assertj-core:3.27.3",
                        "assertj-core"))),
                true);
    }

    static Recipe backend() {
        return recipe(
                "backend-spring-java",
                RecipeKind.BACKEND,
                Set.of(Capability.of("rest-api")),
                List.of(
                        PatchRule.always(new PatchOp.AddDependency(
                                RecipeId.of("backend-spring-java"),
                                "build.gradle.kts",
                                "implementation",
                                "org.springframework.boot:spring-boot-starter-web",
                                "spring-boot-starter-web")),
                        PatchRule.always(new PatchOp.AddDependency(
                                RecipeId.of("backend-spring-java"),
                                "build.gradle.kts",
                                "testImplementation",
                                "org.assertj:assertj-core:3.27.3",
                                "assertj-core")),
                        PatchRule.always(new PatchOp.AddDependency(
                                RecipeId.of("backend-spring-java"),
                                "build.gradle.kts",
                                "implementation",
                                "com.example:inline-only",
                                null))),
                false);
    }

    private static Recipe recipe(
            String id, RecipeKind kind, Set<Capability> provides, List<PatchRule> patches, boolean hasHook) {
        return new Recipe(
                RecipeId.of(id),
                RecipeVersion.parse("1.0.0"),
                null,
                kind,
                id,
                provides,
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                List.of(FileRule.always("files/**")),
                patches,
                hasHook,
                null);
    }
}
