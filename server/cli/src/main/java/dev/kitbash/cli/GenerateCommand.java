package dev.kitbash.cli;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.error.GenerationException;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.render.PebbleRenderStage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** {@code kitbash generate} — the whole pipeline, to a directory or to one deterministic zip. */
final class GenerateCommand {

    int run(Arguments arguments, PrintStream out, PrintStream err) {
        try {
            CatalogLoader.LoadedCatalog catalog = CatalogLocator.load(arguments);
            GenerationPipeline pipeline =
                    GenerationPipeline.over(catalog.catalog(), catalog.content(), new PebbleRenderStage());

            GeneratedProject project = pipeline.generate(Selections.read(arguments.selection()));

            if (arguments.zip()) {
                writeZip(project, arguments.out());
            } else {
                writeTree(project, arguments.out());
            }

            // Hashes and recipe ids only (§10), and on stdout so a script can capture them.
            out.printf(
                    "%s  %d files  %d bytes  selection %s  catalog %s%n",
                    arguments.out(),
                    project.fileCount(),
                    project.totalBytes(),
                    project.selectionHash(),
                    project.lock().catalogDigest());
            return 0;
        } catch (GenerationException e) {
            return ErrorOutput.print(e.error(), err);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Not a rejected selection: a missing file, an unreadable catalog, a bad flag. Those
            // print as a sentence rather than as an envelope, because an envelope with no stage
            // and no code would be the §14 shape with nothing in it.
            return ErrorOutput.print(e.getMessage(), err);
        } catch (IOException e) {
            return ErrorOutput.print("Could not write the project: " + e.getMessage(), err);
        }
    }

    private static void writeZip(GeneratedProject project, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            project.streamTo(out);
        }
    }

    /**
     * Writes the tree, modes and all.
     *
     * <p>The executable bit matters more than it looks: a {@code gradlew} written 0644 makes the
     * first command the README tells you to run fail, and the verification runner would report
     * that as a broken recipe.
     */
    private static void writeTree(GeneratedProject project, Path root) throws IOException {
        Files.createDirectories(root);
        for (Map.Entry<String, GeneratedFile> entry :
                project.workspace().files().entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue().content());
            if (entry.getValue().executable()) {
                try {
                    Files.setPosixFilePermissions(
                            file,
                            Set.of(
                                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                                    java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                                    java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE));
                } catch (UnsupportedOperationException windows) {
                    // No POSIX permissions here. The zip carries the mode regardless, which is
                    // what the container that runs the build will unpack.
                }
            }
        }
    }
}
