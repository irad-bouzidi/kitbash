package dev.kitbash.core.pipeline;

import dev.kitbash.core.patch.PatchOp;
import dev.kitbash.core.plan.RecipeContent;
import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.FileRule;
import dev.kitbash.core.recipe.OptionGroup;
import dev.kitbash.core.recipe.OptionSpec;
import dev.kitbash.core.recipe.OptionType;
import dev.kitbash.core.recipe.PatchRule;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import dev.kitbash.core.recipe.Slot;
import dev.kitbash.core.recipe.SlotType;
import dev.kitbash.core.selection.OptionValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A two-recipe catalog with its files held in a map, shaped like the real thing: a base that owns
 * the shared files, a backend that patches them, an architecture option that selects between two
 * alternative trees, and a conditional patch.
 *
 * <p>In memory rather than on disk because {@link dev.kitbash.core.plan.RecipeContent} exists
 * precisely so the plan stage can be tested without a filesystem (§6) — and a planner test that
 * needs a temp directory is a planner test nobody runs on save.
 */
final class TestCatalogFixture {

    static final RecipeId BASE = RecipeId.of("base");
    static final RecipeId BACKEND = RecipeId.of("backend-spring-java");

    private TestCatalogFixture() {}

    static Catalog catalog() {
        return Catalog.of(
                List.of(base(), backend()),
                "sha256:fixture",
                List.of(new OptionGroup(
                        "stack",
                        "Stack",
                        "What the project is made of.",
                        1,
                        List.of(new Slot(
                                "backend", SlotType.ENUM, "Backend", "The service half.", false, false, "stack")))),
                List.of());
    }

    static Recipe base() {
        return new Recipe(
                BASE,
                RecipeVersion.parse("1.0.0"),
                null,
                RecipeKind.BASE,
                "Project skeleton",
                capabilities("project-root"),
                Set.of(),
                Set.of(),
                List.of(),
                Set.of(),
                List.of(FileRule.always("files/**")),
                List.of(),
                false,
                null);
    }

    static Recipe backend() {
        return new Recipe(
                BACKEND,
                RecipeVersion.parse("1.4.0"),
                "3.5.5",
                RecipeKind.BACKEND,
                "Spring Boot (Java)",
                capabilities("rest-api", "jvm-project"),
                capabilities("project-root"),
                Set.of(),
                List.of(
                        OptionSpec.of(
                                "architecture",
                                OptionType.ENUM,
                                List.of("layered", "hexagonal"),
                                OptionValue.text("layered"),
                                "Architecture",
                                "Determines the package layout and dependency direction."),
                        OptionSpec.of(
                                "docs",
                                OptionType.BOOLEAN,
                                List.of(),
                                OptionValue.flag(false),
                                "Extra docs",
                                "Emit an architecture note beside the README.")),
                Set.of("groupId", "packageName"),
                List.of(
                        FileRule.always("files/**"),
                        new FileRule("arch/layered/**", "architecture == 'layered'"),
                        new FileRule("arch/hexagonal/**", "architecture == 'hexagonal'")),
                List.of(
                        PatchRule.always(new PatchOp.AppendLines(BACKEND, ".gitignore", List.of("build/", ".gradle/"))),
                        new PatchRule(new PatchOp.AppendLines(BACKEND, ".gitignore", List.of("docs/build/")), "docs")),
                false,
                "backend");
    }

    /** The recipe trees, keyed the way a directory would lay them out. */
    static RecipeContent content() {
        Map<RecipeId, Map<String, String>> trees = new LinkedHashMap<>();
        trees.put(
                BASE,
                Map.of(
                        "files/README.md.peb", "# {{ projectName }}\n",
                        "files/.gitignore", "# generated\n"));
        trees.put(
                BACKEND,
                Map.of(
                        "files/build.gradle.kts.peb", "group = \"{{ groupId }}\"\n",
                        "arch/layered/src/main/java/{{ packageName | packagePath }}/web/Controller.java.peb",
                                "package {{ packageName }}.web;\n",
                        "arch/hexagonal/src/main/java/{{ packageName | packagePath }}/adapter/in/web/Controller.java.peb",
                                "package {{ packageName }}.adapter.in.web;\n"));
        return new MapRecipeContent(trees);
    }

    /** Matches a glob the same way the real loader does, so the prefix rule is exercised honestly. */
    private record MapRecipeContent(Map<RecipeId, Map<String, String>> trees) implements RecipeContent {

        @Override
        public List<RecipeFile> filesMatching(RecipeId recipeId, String glob) {
            java.nio.file.PathMatcher matcher =
                    java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob);
            List<RecipeFile> files = new ArrayList<>();
            trees.getOrDefault(recipeId, Map.of()).entrySet().stream()
                    .filter(entry -> matcher.matches(java.nio.file.Path.of(entry.getKey())))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                        files.add(new RecipeFile(entry.getKey(), () -> bytes, false, bytes.length));
                    });
            return files;
        }
    }

    private static Set<Capability> capabilities(String... names) {
        return Arrays.stream(names).map(Capability::of).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
