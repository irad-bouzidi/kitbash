package dev.kitbash.api.generate;

import dev.kitbash.core.pack.DeterministicZipWriter;
import dev.kitbash.core.selection.Selection;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates one zip and exits. Exists so the determinism test can run generation in a JVM of its
 * own: an in-process comparison shares a heap, a set of hash seeds and a clock with the first run,
 * and would pass while the output still varied between real requests.
 */
public final class GenerateToFileMain {

    private GenerateToFileMain() {}

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]);
        Selection selection = new Selection(
                1,
                "customer-management",
                Map.of("backend", "backend-spring-boot-java", "docker", true),
                Map.of("groupId", "com.acme", "packageName", "com.acme.customer", "javaVersion", "21"));

        try (OutputStream out = Files.newOutputStream(target)) {
            DeterministicZipWriter.write(new Phase0ProjectGenerator().generate(selection), "customer-management", out);
        }
    }
}
