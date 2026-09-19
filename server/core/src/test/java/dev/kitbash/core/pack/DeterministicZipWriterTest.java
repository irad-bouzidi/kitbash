package dev.kitbash.core.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.workspace.GeneratedFile;
import dev.kitbash.core.workspace.Workspace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicZipWriterTest {

    @Test
    @DisplayName("the same workspace produces the same bytes")
    void isDeterministic() throws IOException {
        assertThat(zip(sampleWorkspace())).isEqualTo(zip(sampleWorkspace()));
    }

    @Test
    @DisplayName("insertion order does not reach the output")
    void insertionOrderIsIrrelevant() throws IOException {
        Workspace forwards = new Workspace();
        forwards.putText("a.txt", "a");
        forwards.putText("b.txt", "b");
        forwards.putText("c/d.txt", "d");

        Workspace backwards = new Workspace();
        backwards.putText("c/d.txt", "d");
        backwards.putText("b.txt", "b");
        backwards.putText("a.txt", "a");

        assertThat(zip(forwards)).isEqualTo(zip(backwards));
    }

    @Test
    @DisplayName("entries are sorted by path and prefixed with the root directory")
    void entriesAreSortedUnderRoot() throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip(sampleWorkspace())))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }

        assertThat(names).isSorted().allSatisfy(name -> assertThat(name).startsWith("demo/"));
        assertThat(names).contains("demo/", "demo/gradlew", "demo/src/", "demo/src/Main.java");
    }

    @Test
    @DisplayName("every entry carries the same fixed timestamp")
    void timestampsAreFixed() throws IOException {
        Map<String, ZipInspector.Entry> entries = ZipInspector.centralDirectory(zip(sampleWorkspace()));

        assertThat(entries.values()).extracting(ZipInspector.Entry::dosTime).containsOnly(0);
        assertThat(entries.values()).extracting(ZipInspector.Entry::dosDate).containsOnly((1 << 5) | 1);
    }

    @Test
    @DisplayName("gradlew arrives at 0755, an ordinary file at 0644 and a directory at 0755")
    void unixModesSurvive() throws IOException {
        Map<String, ZipInspector.Entry> entries = ZipInspector.centralDirectory(zip(sampleWorkspace()));

        assertThat(entries.get("demo/gradlew").unixMode()).isEqualTo(0_100755);
        assertThat(entries.get("demo/src/Main.java").unixMode()).isEqualTo(0_100644);
        assertThat(entries.get("demo/src/").unixMode()).isEqualTo(0_040755);
    }

    @Test
    @DisplayName("the archive is readable by an ordinary zip reader, contents intact")
    void contentsRoundTrip(@TempDir Path directory) throws IOException {
        Path archive = directory.resolve("out.zip");
        Files.write(archive, zip(sampleWorkspace()));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            byte[] read = zip.getInputStream(zip.getEntry("demo/src/Main.java")).readAllBytes();
            assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("class Main {}\n");
        }
    }

    @Test
    @DisplayName("content that does not compress is stored rather than grown")
    void incompressibleContentIsStored() throws IOException {
        Workspace workspace = new Workspace();
        workspace.putText("tiny.txt", "x");

        ZipInspector.Entry entry = ZipInspector.centralDirectory(zip(workspace)).get("demo/tiny.txt");

        assertThat(entry.method()).isZero();
        assertThat(entry.compressedSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("a path that would escape the project directory is refused")
    void refusesTraversal() {
        Workspace workspace = new Workspace();
        assertThatThrownBy(() -> workspace.putText("../outside.txt", "no"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traverse");
        assertThatThrownBy(() -> workspace.putText("/etc/passwd", "no"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
    }

    private static Workspace sampleWorkspace() {
        Workspace workspace = new Workspace();
        workspace.putText("README.md", "# demo\n\nA project.\n");
        workspace.putText("src/Main.java", "class Main {}\n");
        workspace.put("gradlew", GeneratedFile.executable("#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8)));
        return workspace;
    }

    private static byte[] zip(Workspace workspace) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DeterministicZipWriter.write(workspace, "demo", out);
        return out.toByteArray();
    }
}
