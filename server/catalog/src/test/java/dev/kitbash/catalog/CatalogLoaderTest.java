package dev.kitbash.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.recipe.Capability;
import dev.kitbash.core.recipe.Catalog;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.recipe.RecipeKind;
import dev.kitbash.core.recipe.RecipeVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One test per validation rule §7 lists, because the loader is the only gate between a malformed
 * recipe and a user's broken download — and a gate nobody tested is a gate that is open.
 */
class CatalogLoaderTest {

    private final CatalogLoader loader = new CatalogLoader();

    @Test
    @DisplayName("a well-formed tree loads, sorted by id, with a digest")
    void loadsAValidTree(@TempDir Path root) {
        Catalog catalog =
                loader.load(new RecipeTreeFixture(root).base().backend().root());

        assertThat(catalog.recipes())
                .extracting(recipe -> recipe.id().value())
                .containsExactly("backend-spring-java", "base");
        assertThat(catalog.digest()).startsWith("sha256:").hasSize(71);
        assertThat(catalog.providersOf(Capability.of("rest-api")))
                .extracting(recipe -> recipe.id().value())
                .containsExactly("backend-spring-java");
    }

    @Test
    @DisplayName("manifest fields land on the domain type exactly as §4 specifies them")
    void readsEveryManifestField(@TempDir Path root) {
        Catalog catalog =
                loader.load(new RecipeTreeFixture(root).base().backend().root());

        Recipe backend = catalog.find(RecipeId.of("backend-spring-java")).orElseThrow();
        assertThat(backend.version()).isEqualTo(RecipeVersion.parse("1.4.0"));
        assertThat(backend.frameworkVersion()).isEqualTo("3.5.5");
        assertThat(backend.kind()).isEqualTo(RecipeKind.BACKEND);
        assertThat(backend.label()).isEqualTo("Spring Boot (Java)");
        assertThat(backend.provides())
                .extracting(Capability::name)
                .containsExactly("http-server", "jvm-project", "rest-api");
        assertThat(backend.requiredVariables()).containsExactly("groupId", "packageName");
        assertThat(backend.options()).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo("architecture");
            assertThat(option.help()).isNotBlank();
        });
        assertThat(backend.patches()).singleElement().satisfies(patch -> {
            assertThat(patch.op().target()).isEqualTo(".gitignore");
            assertThat(patch.op().owner()).isEqualTo(RecipeId.of("backend-spring-java"));
        });
    }

    @Nested
    @DisplayName("refuses to start, naming the file, the field and the fix")
    class Rejects {

        @Test
        @DisplayName("a recipe id that is already taken")
        void duplicateRecipeId(@TempDir Path root) {
            // Unreachable through the filesystem, since a recipe's id is its directory name —
            // which is exactly why the rule is asserted against the validator directly rather
            // than left as a comment claiming it cannot happen.
            List<LoadedRecipe> twice = List.of(
                    loadOne(root, "base"),
                    new LoadedRecipe(loadOne(root, "base").recipe(), root, "other-hash"));

            assertThatThrownBy(() -> CatalogLoader.validate(twice))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("base")
                    .hasMessageContaining("already used by")
                    .hasMessageContaining("globally unique");
        }

        @Test
        @DisplayName("a kind the pipeline has no slot for")
        void unknownKind(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .manifestOnly(
                            "base",
                            """
                            id: base
                            version: 1.0.0
                            kind: backendd
                            label: Skeleton
                            """)
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("base/recipe.yaml")
                    .hasMessageContaining("kind");
        }

        @Test
        @DisplayName("a version that is not plain semver")
        void malformedSemver(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .manifestOnly(
                            "base",
                            """
                            id: base
                            version: "1.4"
                            kind: base
                            label: Skeleton
                            """)
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("version");
        }

        @Test
        @DisplayName("a when expression reading an option the recipe never declared")
        void whenReferencesUndeclaredOption(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .recipe(
                            "base",
                            """
                            id: base
                            version: 1.0.0
                            kind: base
                            label: Skeleton
                            provides: [project-root]
                            files:
                              - from: files/**
                                when: architecture == 'hexagonal'
                            """,
                            "files/README.md",
                            "hi\n")
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("architecture")
                    .hasMessageContaining("does not declare")
                    .hasMessageContaining("options[]");
        }

        @Test
        @DisplayName("a required capability nothing in the catalog provides")
        void unsatisfiableRequires(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .recipe(
                            "base",
                            """
                            id: base
                            version: 1.0.0
                            kind: base
                            label: Skeleton
                            requires: [database]
                            files:
                              - from: files/**
                            """,
                            "files/README.md",
                            "hi\n")
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("database")
                    .hasMessageContaining("no recipe in this catalog provides it");
        }

        @Test
        @DisplayName("a patch naming an operation that does not exist")
        void unknownPatchOperation(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .recipe(
                            "base",
                            """
                            id: base
                            version: 1.0.0
                            kind: base
                            label: Skeleton
                            files:
                              - from: files/**
                            patches:
                              - op: appendText
                                target: .gitignore
                                lines: ["build/"]
                            """,
                            "files/README.md",
                            "hi\n")
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("op")
                    .hasMessageContaining("appendLines");
        }

        @Test
        @DisplayName("a files glob that matches nothing")
        void globMatchesNothing(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .recipe(
                            "base",
                            """
                            id: base
                            version: 1.0.0
                            kind: base
                            label: Skeleton
                            files:
                              - from: files/**
                              - from: arch/hexagonal/**
                            """,
                            "files/README.md",
                            "hi\n")
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("arch/hexagonal/**")
                    .hasMessageContaining("matches no file")
                    .hasMessageContaining("silently is not shipping");
        }

        @Test
        @DisplayName("a manifest whose id disagrees with its directory")
        void idDisagreesWithDirectory(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .manifestOnly(
                            "base",
                            """
                            id: project-base
                            version: 1.0.0
                            kind: base
                            label: Skeleton
                            """)
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("directory is called 'base'");
        }

        @Test
        @DisplayName("a key the schema has never heard of, rather than ignoring it")
        void unknownManifestKey(@TempDir Path root) {
            // A typo in a manifest key is the silent failure this schema exists to prevent:
            // `provide:` instead of `provides:` would otherwise load as a recipe that provides
            // nothing, and fail much later as an unsatisfied capability somewhere else.
            Path tree = new RecipeTreeFixture(root)
                    .manifestOnly(
                            "base",
                            """
                            id: base
                            version: 1.0.0
                            kind: base
                            label: Skeleton
                            provide: [project-root]
                            """)
                    .root();

            assertThatThrownBy(() -> loader.load(tree))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("schema");
        }

        @Test
        @DisplayName("an option with no help text")
        void optionWithoutHelp(@TempDir Path root) {
            Path tree = new RecipeTreeFixture(root)
                    .manifestOnly(
                            "base",
                            """
                            id: base
                            version: 1.0.0
                            kind: base
                            label: Skeleton
                            options:
                              - id: docker
                                type: boolean
                                default: true
                            """)
                    .root();

            assertThatThrownBy(() -> loader.load(tree)).isInstanceOf(RecipeLoadException.class);
        }

        @Test
        @DisplayName("a directory with no manifest in it at all")
        void directoryWithoutManifest(@TempDir Path root) throws IOException {
            Files.createDirectories(root.resolve("half-finished"));

            assertThatThrownBy(() -> loader.load(root))
                    .isInstanceOf(RecipeLoadException.class)
                    .hasMessageContaining("recipe.yaml")
                    .hasMessageContaining("is missing");
        }

        private LoadedRecipe loadOne(Path root, String id) {
            return loader.loadAll(new RecipeTreeFixture(root).base().root()).recipes().stream()
                    .filter(entry -> entry.recipe().id().value().equals(id))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("catalog digest")
    class Digest {

        @Test
        @DisplayName("is identical for the same tree loaded twice from different directories")
        void isStableAcrossTrees(@TempDir Path first, @TempDir Path second) {
            String one = loader.load(
                            new RecipeTreeFixture(first).base().backend().root())
                    .digest();
            String two = loader.load(
                            new RecipeTreeFixture(second).base().backend().root())
                    .digest();

            // §7 requires this across machines, which is why paths are relativised and bytes are
            // hashed rather than strings: an absolute path or a platform charset in the material
            // would make two checkouts of the same commit disagree.
            assertThat(one).isEqualTo(two);
        }

        @Test
        @DisplayName("changes when any recipe byte changes, template bodies included")
        void movesWhenAnyByteChanges(@TempDir Path root) throws IOException {
            RecipeTreeFixture fixture = new RecipeTreeFixture(root).base().backend();
            String before = loader.load(fixture.root()).digest();

            Files.writeString(root.resolve("base/files/README.md.peb"), "# {{ projectName }} service\n");

            assertThat(loader.load(fixture.root()).digest()).isNotEqualTo(before);
        }

        @Test
        @DisplayName("changes when a template is renamed, even with identical content")
        void movesWhenAFileIsRenamed(@TempDir Path root) throws IOException {
            RecipeTreeFixture fixture = new RecipeTreeFixture(root).base().backend();
            String before = loader.load(fixture.root()).digest();

            Files.move(root.resolve("base/files/README.md.peb"), root.resolve("base/files/READ-ME.md.peb"));

            assertThat(loader.load(fixture.root()).digest()).isNotEqualTo(before);
        }
    }

    @Test
    @DisplayName("an empty tree is a catalog with nothing in it, not a crash")
    void emptyTreeLoads(@TempDir Path root) {
        Catalog catalog = loader.load(root);

        assertThat(catalog.size()).isZero();
        assertThat(catalog.allCapabilities()).isEqualTo(Set.of());
    }
}
