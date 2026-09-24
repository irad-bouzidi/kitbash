package dev.kitbash.catalog;

import dev.kitbash.core.recipe.Recipe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses a {@code recipe.yaml} that arrived in a request body rather than from the repository
 * (kitbash-47).
 *
 * <p>It exists to <b>reuse</b> {@link ManifestReader} rather than to reimplement it. A second
 * parser for contributed manifests is the shape of defect §46's implementation notes warn about in
 * a different context — <i>two code paths producing "the same" thing is how they stop being the
 * same</i> — and here the two would diverge on exactly the validation a submission most needs.
 * Everything the schema refuses for a shipped recipe is refused for a contributed one by the same
 * code, and a rule added to the schema tomorrow applies to both without anybody remembering.
 *
 * <p>Lives in this package because {@code ManifestReader} is package-private and should stay that
 * way; this is the one door, and it is a narrow one.
 */
public final class SubmittedManifest {

    private static final ManifestSchema SCHEMA = ManifestSchema.load();

    private SubmittedManifest() {}

    /**
     * @param yaml the submitted manifest
     * @param displayPath what failures should call it — {@code "@platform/audit-log/recipe.yaml"}
     *     rather than a temp file nobody can look at
     * @throws RecipeLoadException with the field and a hint, exactly as a bad shipped recipe fails
     */
    public static Recipe parse(String yaml, String displayPath) {
        // Through a file because that is the reader's signature, and changing the signature to
        // take bytes would touch the loader that every shipped recipe goes through — a wider
        // blast radius than this feature has earned. The file exists for microseconds, in the
        // API process rather than the sandbox, and holds a manifest that has not yet been
        // trusted with anything.
        Path temporary = null;
        try {
            temporary = Files.createTempFile("kitbash-manifest-", ".yaml");
            Files.writeString(temporary, yaml, StandardCharsets.UTF_8);
            return new ManifestReader(SCHEMA).read(temporary, displayPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the submitted manifest", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A temp file holding YAML somebody just sent us. Worth no more than this.
                }
            }
        }
    }
}
