package dev.kitbash.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.recipe.Recipe;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.render.PebbleRenderStage;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code kitbash recipe check} — the verdicts CI gives, on one recipe, in seconds (§43).
 *
 * <h2>The rule this is built to</h2>
 *
 * <p>§43: <i>the harness must give the same verdicts CI gives. An SDK that passes locally and fails
 * in CI teaches people to distrust the tools.</i> So every check below runs the <b>same code</b>
 * the pipeline runs — the real loader, the real resolver, the real renderer, the real patch
 * applier — rather than a lighter approximation of it. What makes it fast is not doing less, it is
 * doing it for one recipe with no server, no database and no containers.
 *
 * <p>What it cannot tell you is whether the generated project <em>builds</em>. That needs a
 * container and minutes, and it is what the matrix is for; the last line of the report says so
 * rather than letting a green run imply more than it proved.
 */
final class RecipeCheck {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecipeCheck() {}

    /** One check's verdict, and what it actually looked at. */
    private record Verdict(String name, boolean passed, String detail, boolean unwired) {

        Verdict(String name, boolean passed, String detail) {
            this(name, passed, detail, false);
        }

        /** Not broken, just not connected to anything yet — the expected first run of a scaffold. */
        static Verdict notWired(String name, String detail) {
            return new Verdict(name, false, detail, true);
        }

        static Verdict pass(String name, String detail) {
            return new Verdict(name, true, detail);
        }

        static Verdict fail(String name, String detail) {
            return new Verdict(name, false, detail);
        }
    }

    static int run(Arguments arguments, String recipeId, PrintStream out, PrintStream err) {
        CatalogLoader.LoadedCatalog loaded;
        try {
            loaded = CatalogLocator.load(arguments);
        } catch (RuntimeException unloadable) {
            // The manifest did not parse or did not validate. That is the first check, and it
            // failed before any of the others could run.
            err.printf("manifest: FAILED%n  %s%n", unloadable.getMessage());
            return 1;
        }

        Optional<Recipe> recipe = loaded.catalog().recipes().stream()
                .filter(candidate -> candidate.id().value().equals(recipeId))
                .findFirst();
        if (recipe.isEmpty()) {
            err.printf(
                    "No recipe '%s' in %s.%n  There is: %s%n",
                    recipeId,
                    CatalogLocator.locate(arguments),
                    String.join(
                            ", ",
                            loaded.catalog().recipes().stream()
                                    .map(known -> known.id().value())
                                    .sorted()
                                    .toList()));
            return 1;
        }

        Path root = CatalogLocator.locate(arguments).getParent();
        List<Path> references = referencesUsing(root, recipeId);

        List<Verdict> verdicts = new ArrayList<>();
        verdicts.add(Verdict.pass("manifest", "validates against recipes/_schema/recipe.schema.json"));

        if (references.isEmpty()) {
            // Named rather than skipped silently. §4's workflow is to derive a recipe from a real
            // project, so a recipe no reference exercises has no selection anybody has proved is
            // buildable — and the checks below would be asserting against a fixture invented here.
            // Not "FAILED": nothing is broken, the recipe is simply not wired in yet, and a
            // scaffolded recipe reaching this line is the expected first run. Still non-zero,
            // because a recipe no reference exercises has not been proved by anything.
            verdicts.add(Verdict.notWired(
                    "reference",
                    "no reference project lists '" + recipeId + "' in its recipeIds, so there "
                            + "is no%n  selection to render it from. docs/authoring-recipes.md step 3.".formatted()));
        }

        for (Path reference : references) {
            check(loaded, reference, recipeId, verdicts);
        }

        verdicts.forEach(verdict -> out.printf(
                "%-14s %s%n  %s%n",
                verdict.name(), verdict.passed() ? "ok" : verdict.unwired() ? "--" : "FAILED", verdict.detail()));

        boolean green = verdicts.stream().allMatch(Verdict::passed);
        out.println();
        out.println(
                green
                        ? "All checks passed. This does not mean the generated project builds — that is a "
                                + "matrix cell, and it needs a container."
                        : verdicts.stream().anyMatch(Verdict::unwired)
                                ? "Nothing is broken; this recipe is not wired in yet. "
                                        + "docs/authoring-recipes.md has the four edits."
                                : "Some checks failed.");
        return green ? 0 : 1;
    }

    /**
     * Every check that a reference project can answer for this recipe.
     *
     * <p>One generation, several questions. Generating once and interrogating the result is not an
     * optimisation — it is what makes the answers consistent with each other, because a second
     * generation could differ and then two checks would be describing two trees.
     */
    private static void check(
            CatalogLoader.LoadedCatalog loaded, Path reference, String recipeId, List<Verdict> verdicts) {
        String name = reference.getFileName().toString();
        GenerationPipeline pipeline =
                GenerationPipeline.over(loaded.catalog(), loaded.content(), new PebbleRenderStage());

        SelectionEnvelope envelope;
        try {
            envelope = selectionOf(reference);
        } catch (RuntimeException unreadable) {
            verdicts.add(Verdict.fail("selection", name + ": " + unreadable.getMessage()));
            return;
        }

        GeneratedProject first;
        try {
            first = pipeline.generate(envelope);
        } catch (GenerationException refused) {
            // Includes PATCH_COLLISION and PATCH_TARGET_MISSING, which are the ownership rules:
            // two recipes cannot own one key, and a patch cannot target a file nothing produces.
            verdicts.add(Verdict.fail(
                    "renders (" + name + ")",
                    refused.error().code().name() + ": " + refused.error().message() + "%n  → ".formatted()
                            + refused.error().hint()));
            return;
        }
        verdicts.add(Verdict.pass(
                "renders (" + name + ")",
                "%d files, %d bytes, no patch collided and every patch target existed"
                        .formatted(first.fileCount(), first.totalBytes())));

        // §4's byte-equality claim, for this selection. Determinism is what the zip cache and the
        // reference equality test both rest on, and it is cheap enough to check every time.
        GeneratedProject second = pipeline.generate(envelope);
        if (!sameTree(first, second)) {
            verdicts.add(Verdict.fail(
                    "deterministic (" + name + ")",
                    "two generations of one selection differed — §4 requires byte equality"));
        } else {
            verdicts.add(Verdict.pass("deterministic (" + name + ")", "two generations produced identical bytes"));
        }

        verdicts.add(referenceEquality(reference, first, recipeId));
    }

    /**
     * The check §4's workflow turns on: does the recipe still reproduce the project it came from?
     *
     * <p>This is the one an author runs after editing a template. The reference project is a real
     * project a person maintains by hand; the recipes were extracted from it, and a difference
     * means the extraction has drifted from the thing it claims to produce.
     */
    private static Verdict referenceEquality(Path reference, GeneratedProject generated, String recipeId) {
        String name = reference.getFileName().toString();
        List<String> excluded = excluded(reference);

        Map<String, String> onDisk = readTree(reference, excluded);
        Map<String, String> produced = new java.util.TreeMap<>();
        generated.workspace().files().forEach((path, file) -> {
            // The git skeleton is generated and never checked in: a reference project is a working
            // tree, and its own .git belongs to this repository. Excluded here for the same reason
            // and in the same way as the CI test, because a harness that excluded a different set
            // would give a different verdict — which is the one thing §43 forbids.
            if (!path.startsWith(".git/")) {
                produced.put(path, fingerprint(file));
            }
        });

        List<String> differences = new ArrayList<>();
        for (String path : new java.util.TreeSet<>(union(onDisk.keySet(), produced.keySet()))) {
            String expected = onDisk.get(path);
            String actual = produced.get(path);
            if (expected == null) {
                differences.add("+ " + path + "  (generated, not in the reference)");
            } else if (actual == null) {
                // The direction people forget. A recipe that stops emitting a file passes every
                // check that only walks what was generated.
                differences.add("- " + path + "  (in the reference, not generated)");
            } else if (!expected.equals(actual)) {
                differences.add("~ " + path);
            }
        }

        if (differences.isEmpty()) {
            return Verdict.pass(
                    "reference (" + name + ")",
                    "generating with the checked-in variables reproduces the reference project, " + produced.size()
                            + " files");
        }
        List<String> shown = differences.subList(0, Math.min(8, differences.size()));
        return Verdict.fail(
                "reference (" + name + ")",
                "%d file%s differ. Edit the reference by hand, prove it builds, then port the diff%n  into %s.%n  %s%s"
                        .formatted(
                                differences.size(),
                                differences.size() == 1 ? "" : "s",
                                recipeId,
                                String.join("\n  ", shown),
                                differences.size() > shown.size()
                                        ? "%n  … and %d more".formatted(differences.size() - shown.size())
                                        : ""));
    }

    /**
     * Content and mode, as one comparable value.
     *
     * <p>The executable bit is part of the comparison because CI compares it: a {@code gradlew}
     * that stopped being executable is a project whose first documented command fails, and it is
     * invisible to a diff of bytes alone.
     */
    private static String fingerprint(GeneratedFile file) {
        return (file.executable() ? "x:" : "-:") + java.util.Base64.getEncoder().encodeToString(file.content());
    }

    private static java.util.Set<String> union(java.util.Set<String> left, java.util.Set<String> right) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(left);
        all.addAll(right);
        return all;
    }

    /** The reference project as it is on disk, minus whatever it excludes from extraction. */
    private static Map<String, String> readTree(Path reference, List<String> excluded) {
        Map<String, String> tree = new java.util.TreeMap<>();
        try (var paths = Files.walk(reference)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = reference.relativize(path).toString().replace('\\', '/');
                if (excluded.stream().anyMatch(relative::startsWith) || relative.startsWith(".git/")) {
                    return;
                }
                try {
                    tree.put(
                            relative,
                            (Files.isExecutable(path) ? "x:" : "-:")
                                    + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read " + path, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + reference, e);
        }
        return tree;
    }

    private static boolean sameTree(GeneratedProject first, GeneratedProject second) {
        Map<String, GeneratedFile> left = first.workspace().files();
        Map<String, GeneratedFile> right = second.workspace().files();
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        return left.entrySet().stream()
                .allMatch(entry -> java.util.Arrays.equals(
                        entry.getValue().content(), right.get(entry.getKey()).content()));
    }

    /** The reference projects whose {@code reference-variables.json} lists this recipe. */
    private static List<Path> referencesUsing(Path root, String recipeId) {
        Path references = root.resolve("reference");
        if (!Files.isDirectory(references)) {
            return List.of();
        }
        try (var directories = Files.list(references)) {
            return directories
                    .filter(Files::isDirectory)
                    .filter(directory -> listsRecipe(directory, recipeId))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + references, e);
        }
    }

    private static boolean listsRecipe(Path reference, String recipeId) {
        Path metadata = reference.resolve("reference-variables.json");
        if (!Files.isRegularFile(metadata)) {
            return false;
        }
        try {
            for (JsonNode id : JSON.readTree(metadata.toFile()).path("recipeIds")) {
                if (id.asText().equals(recipeId)) {
                    return true;
                }
            }
        } catch (IOException unreadable) {
            return false;
        }
        return false;
    }

    private static SelectionEnvelope selectionOf(Path reference) {
        try {
            JsonNode selection = JSON.readTree(
                            reference.resolve("reference-variables.json").toFile())
                    .path("selection");
            Map<String, Object> options = new LinkedHashMap<>();
            selection
                    .path("options")
                    .properties()
                    .forEach(entry -> options.put(
                            entry.getKey(),
                            entry.getValue().isBoolean()
                                    ? entry.getValue().asBoolean()
                                    : entry.getValue().asText()));
            Map<String, String> variables = new LinkedHashMap<>();
            selection
                    .path("variables")
                    .properties()
                    .forEach(entry ->
                            variables.put(entry.getKey(), entry.getValue().asText()));
            return SelectionEnvelope.current(selection.path("projectName").asText(), options, variables);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the reference selection", e);
        }
    }

    private static List<String> excluded(Path reference) {
        List<String> excluded = new ArrayList<>(List.of("reference-variables.json", "REFERENCE.md"));
        try {
            JsonNode metadata =
                    JSON.readTree(reference.resolve("reference-variables.json").toFile());
            metadata.path("excludeFromExtraction").forEach(path -> excluded.add(path.asText()));
        } catch (IOException unreadable) {
            // The defaults above still hold.
        }
        return excluded;
    }

    static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
