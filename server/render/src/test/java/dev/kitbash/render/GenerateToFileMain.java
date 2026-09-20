package dev.kitbash.render;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the fixture project into a zip at {@code args[0]}, in a JVM of its own.
 *
 * <p>Exists so {@link PipelineDeterminismTest} can compare two processes rather than two calls.
 */
public final class GenerateToFileMain {

    private GenerateToFileMain() {}

    public static void main(String[] args) throws Exception {
        try (OutputStream out = Files.newOutputStream(Path.of(args[0]))) {
            PipelineFixture.pipeline()
                    .generate(PipelineFixture.selection("customer-management"))
                    .streamTo(out);
        }
    }
}
