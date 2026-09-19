package dev.kitbash.api.generate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.pack.DeterministicZipWriter;
import dev.kitbash.core.selection.Selection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reproducibility guarantee and the cache key both rest on this (§4), so it is asserted in the
 * merge request that introduces generation rather than left for the caching task.
 */
class GenerationDeterminismTest {

    @Test
    @DisplayName("generating twice in one JVM produces byte-identical zips")
    void sameJvmIsDeterministic() throws IOException {
        assertThat(generate()).isEqualTo(generate());
    }

    @Test
    @DisplayName("two separate JVM invocations produce byte-identical zips")
    void separateJvmsAreDeterministic(@TempDir Path directory) throws Exception {
        // Two runs in one JVM share a heap, a set of hash seeds and a clock. A dependence on
        // any of the three — an unsorted HashMap, an identity hash code in a name, a timestamp
        // written into a file — survives an in-process comparison and fails in production.
        Path first = fork(directory.resolve("first.zip"));
        Path second = fork(directory.resolve("second.zip"));

        assertThat(sha256(first))
                .describedAs("two JVMs generated different bytes; something in the pipeline is not deterministic")
                .isEqualTo(sha256(second));
        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
    }

    @Test
    @DisplayName("a different selection produces a different zip, so equality is not vacuous")
    void differentSelectionsDiffer() throws IOException {
        assertThat(generate()).isNotEqualTo(generate("other-service"));
    }

    private static Path fork(Path target) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = System.getProperty("kitbash.test.classpath");
        assertThat(classpath)
                .describedAs("kitbash.test.classpath is set by the build")
                .isNotBlank();

        Process process = new ProcessBuilder(
                        java.toString(), "-cp", classpath, GenerateToFileMain.class.getName(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .describedAs("forked generation failed:%n%s", output)
                .isZero();
        return target;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static byte[] generate() throws IOException {
        return generate("customer-management");
    }

    private static byte[] generate(String projectName) throws IOException {
        Selection selection = new Selection(
                1, projectName, Map.of(), Map.of("groupId", "com.acme", "packageName", "com.acme.customer"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeterministicZipWriter.write(new Phase0ProjectGenerator().generate(selection), projectName, out);
        return out.toByteArray();
    }
}
