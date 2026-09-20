package dev.kitbash.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole pipeline, with the real template engine, asserted byte-identical across two JVMs.
 *
 * <p>Two runs in one process share a heap, a set of hash seeds and a clock. A dependence on any of
 * the three — an unsorted map reaching the output, an identity hash code in a name, a timestamp
 * written into a file — survives an in-process comparison and then fails in production, where the
 * cache serves one user's project to another or a replay produces different bytes (§4, §7, §10).
 *
 * <p>This is the test that would have caught the {@code Map.copyOf} ordering bug {@code kitbash-10}
 * found by accident.
 */
class PipelineDeterminismTest {

    @Test
    @DisplayName("two separate JVM invocations produce byte-identical zips")
    void separateJvmsAgree(@TempDir Path directory) throws Exception {
        Path first = fork(directory.resolve("first.zip"));
        Path second = fork(directory.resolve("second.zip"));

        assertThat(sha256(first))
                .describedAs("two JVMs generated different bytes; something in the pipeline is not deterministic")
                .isEqualTo(sha256(second));
    }

    @Test
    @DisplayName("generating twice in one JVM produces byte-identical zips")
    void sameJvmAgrees() throws IOException {
        assertThat(generate("customer-management")).isEqualTo(generate("customer-management"));
    }

    @Test
    @DisplayName("a different project name produces a different zip, so equality is not vacuous")
    void differentSelectionsDiffer() throws IOException {
        assertThat(generate("customer-management")).isNotEqualTo(generate("order-management"));
    }

    @Test
    @DisplayName("the commit id is fixed too, so two people generating the same project agree on it")
    void commitIdIsStable() {
        assertThat(PipelineFixture.pipeline()
                        .generate(PipelineFixture.selection("customer-management"))
                        .commitId())
                .isEqualTo(PipelineFixture.pipeline()
                        .generate(PipelineFixture.selection("customer-management"))
                        .commitId());
    }

    private static byte[] generate(String projectName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PipelineFixture.pipeline()
                .generate(PipelineFixture.selection(projectName))
                .streamTo(out);
        return out.toByteArray();
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
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
