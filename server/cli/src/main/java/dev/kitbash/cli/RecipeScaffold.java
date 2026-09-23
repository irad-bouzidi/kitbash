package dev.kitbash.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code kitbash recipe new} — an empty directory to a manifest that validates (§4, §43).
 *
 * <h2>Why it takes a reference project</h2>
 *
 * <p>§4 established the workflow and §43 exists to support it: <i>edit a real reference project,
 * run the equality test, port the diff.</i> A recipe is not written from a blank manifest — it is
 * <b>extracted</b> from a project somebody maintains by hand, because that is the only way the
 * output is known to build before a single template exists.
 *
 * <p>So this scaffolds the starting point of an extraction, and names the project to extract from
 * in the manifest itself.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>An earlier draft wired the recipe all the way in: it declared a slot in {@code _catalog.yaml},
 * added the id to the reference project's {@code recipeIds}, and turned the slot on in its
 * selection — so that the first {@code recipe check} was green. It worked, and it was wrong.
 *
 * <p>{@code _catalog.yaml} is the wizard's editorial structure: its sections, their order, and what
 * each control says. A slot appended to whichever group happens to be last is a decision about how
 * the wizard reads, made silently by a scaffold. And a recipe wired into a reference project before
 * it does anything makes that project's checked-in tree carry a file nobody chose.
 *
 * <p>§43 asks that the scaffold <i>produce something that passes validation immediately</i>. It
 * does. Wiring is four edits, and they are printed with the reason for each, because an author who
 * has made them once understands the model and an author who had them made for them does not.
 */
final class RecipeScaffold {

    private RecipeScaffold() {}

    static int run(Arguments arguments, String id, String reference, PrintStream out, PrintStream err) {
        Path catalog = CatalogLocator.locate(arguments);
        Path referenceProject = catalog.getParent().resolve("reference").resolve(reference);

        if (!Files.isDirectory(referenceProject)) {
            err.printf(
                    "No reference project at %s.%n  There is: %s%n",
                    referenceProject, String.join(", ", referenceProjects(catalog.getParent())));
            return 1;
        }

        Path directory = catalog.resolve(id);
        if (Files.exists(directory)) {
            err.printf("%s already exists. Pick another id, or edit the recipe that is there.%n", directory);
            return 1;
        }

        try {
            Files.createDirectories(directory.resolve("files"));
            Files.writeString(directory.resolve("recipe.yaml"), manifest(id, reference), StandardCharsets.UTF_8);
            // One file, because a `from:` glob that matches nothing is refused at load — a file set
            // somebody meant to ship and silently is not. §43 wants the scaffold to validate
            // immediately, and an empty directory does not.
            Files.writeString(
                    directory.resolve("files").resolve("PLACEHOLDER-" + id + ".md"),
                    "Replace me with a file taken from the reference project.\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scaffold " + directory, e);
        }

        out.printf(
                """
                Created recipes/%s/
                  recipe.yaml   validates now: `kitbash recipe check %s` will say so
                  files/        one placeholder, so the manifest validates. Replace it with a file
                                taken from the reference project — that is the direction §4 requires

                Nothing selects it yet. Four edits wire it in, and each is worth understanding:

                  1. recipes/_catalog.yaml — declare a slot in the group it belongs to.
                     A slot is how a choice reaches a user: the wizard renders one control per slot
                     and knows nothing else about your recipe.
                  2. recipes/%s/recipe.yaml — name that slot.
                  3. reference/%s/reference-variables.json — turn the slot on under "options",
                     and add '%s' to "recipeIds".
                  4. Put a file in reference/%s by hand, prove it builds, then copy it into
                     recipes/%s/files/ and run the check.

                  kitbash recipe check %s

                docs/authoring-recipes.md follows one small recipe through all four, with the
                output of each step.
                """,
                id, id, id, reference, id, reference, id, id);
        return 0;
    }

    /**
     * A manifest that validates on the first run.
     *
     * <p>Every field the schema requires and nothing it does not. A scaffold that emitted a
     * commented-out example of every optional key would be a file whose first job is deleting most
     * of itself; {@code docs/recipe-format.md} is where the full vocabulary belongs.
     *
     * <p>The {@code $schema} line is first, because it is what gives an author completion and
     * validation while typing — which is most of what §43 means by editor integration.
     */
    private static String manifest(String id, String reference) {
        return """
            # yaml-language-server: $schema=../_schema/recipe.schema.json
            #
            # Extracted from reference/%s. Every file below should have been a hand-written file in
            # that project first: `kitbash recipe check %s` is what proves this still reproduces it.
            id: %s
            version: 0.1.0
            kind: feature
            label: %s
            # slot: name a slot declared in _catalog.yaml. Until then nothing selects this recipe.
            requires: []
            provides: []

            files:
              # A glob under this recipe. Where each file lands is decided by where it sits beneath
              # `files/`, which is what makes a recipe readable as the tree it produces.
              - from: files/**
            """
                .formatted(reference, id, id, id);
    }

    private static List<String> referenceProjects(Path root) {
        Path references = root.resolve("reference");
        if (!Files.isDirectory(references)) {
            return List.of();
        }
        try (var directories = Files.list(references)) {
            return directories
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + references, e);
        }
    }
}
