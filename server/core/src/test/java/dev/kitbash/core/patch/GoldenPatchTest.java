package dev.kitbash.core.patch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.core.recipe.RecipeId;
import dev.kitbash.core.workspace.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * All eight operations applied to one project, with the result checked against files a person read.
 *
 * <p>§10 asks for this early and for a reason: unit tests prove an applier did what it was told,
 * and a golden file is the only cheap way to see whether what it produced is something a human
 * would have written. A generator whose output is diff-hostile is a generator nobody reviews the
 * output of, and then nobody notices when it goes wrong.
 *
 * <p>The goldens are regenerated with {@code ./gradlew :core:test -Dkitbash.golden.update=true},
 * which is a deliberate two-step: the diff lands in the commit and somebody has to look at it.
 *
 * <p>Each patched file is then re-read with its format's real parser. That answers a different
 * question from the byte comparison — "is this still valid?" rather than "is this what we expect?"
 * — and it is the one that stops a plausible-looking golden from hiding broken syntax. Whether the
 * result actually <i>builds</i> is the verification matrix's question (§12, kitbash-18).
 */
class GoldenPatchTest {

    private static final RecipeId AUTH = RecipeId.of("feature-auth-jwt");
    private static final RecipeId WEB = RecipeId.of("frontend-react-vite");
    private static final boolean UPDATE = Boolean.getBoolean("kitbash.golden.update");

    private static Workspace project() {
        Workspace workspace = new Workspace();
        workspace.putText("build.gradle.kts", resource("golden/source/build.gradle.kts"));
        workspace.putText("pom.xml", resource("golden/source/pom.xml"));
        workspace.putText("package.json", resource("golden/source/package.json"));
        workspace.putText("src/main/resources/application.yaml", resource("golden/source/application.yaml"));
        workspace.putText("compose.yaml", resource("golden/source/compose.yaml"));
        workspace.putText(".gitignore", resource("golden/source/gitignore.txt"));
        workspace.putText(".env.example", resource("golden/source/env.example.txt"));
        workspace.putText("src/main/java/Application.java", resource("golden/source/Application.java.txt"));
        return workspace;
    }

    /** One of each, in the order a resolved catalog would contribute them. */
    private static List<PatchOp> everyOperation() {
        return List.of(
                new PatchOp.AddDependency(
                        AUTH,
                        "build.gradle.kts",
                        "implementation",
                        "org.springframework.boot:spring-boot-starter-oauth2-resource-server",
                        "spring-boot-starter-oauth2-resource-server"),
                new PatchOp.AddDependency(AUTH, "pom.xml", "test", "org.testcontainers:postgresql:1.21.3", null),
                new PatchOp.AddDependency(WEB, "package.json", "devDependencies", "vitest@^3.0.0", null),
                new PatchOp.MergeYaml(
                        AUTH,
                        "src/main/resources/application.yaml",
                        Map.of(
                                "spring",
                                Map.of(
                                        "security",
                                        Map.of(
                                                "oauth2",
                                                Map.of(
                                                        "resourceserver",
                                                        Map.of("jwt", Map.of("issuer-uri", "${JWT_ISSUER}"))))))),
                new PatchOp.MergeJson(WEB, "package.json", Map.of("type", "module")),
                new PatchOp.AddScript(WEB, "package.json", "test", "vitest run"),
                new PatchOp.InsertAtMarker(
                        AUTH,
                        "src/main/java/Application.java",
                        "// kitbash:imports",
                        List.of("import org.springframework.security.config.annotation.web.builders.HttpSecurity;")),
                new PatchOp.AppendLines(WEB, ".gitignore", List.of("node_modules/", "dist/")),
                new PatchOp.AddEnvVar(
                        AUTH,
                        ".env.example",
                        "compose.yaml",
                        "api",
                        "JWT_ISSUER",
                        "http://localhost:8081/realms/kitbash",
                        "Issuer the resource server validates tokens against"),
                new PatchOp.AddComposeService(
                        WEB,
                        "compose.yaml",
                        "web",
                        ordered("build", "./web", "ports", List.of("5173:5173")),
                        List.of("api")));
    }

    /** An ordered map, because a compose definition's key order ends up in the output file. */
    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("all eight operations on one project produce output a person would have written")
    void matchesTheGolden() {
        Workspace workspace = project();

        PatchApplier.apply(workspace, everyOperation());

        workspace.files().forEach((path, file) -> {
            String actual = new String(file.content(), StandardCharsets.UTF_8);
            String golden = "golden/patched/" + goldenName(path);
            if (UPDATE) {
                // Writing and asserting in the same run would compare against the stale copy the
                // test classpath was built from; regenerate, then re-run without the flag.
                writeGolden(golden, actual);
                return;
            }
            assertThat(actual).as("%s", path).isEqualTo(resource(golden));
        });
    }

    @Test
    @DisplayName("every patched file still parses under its own format's real parser")
    void stillParses() {
        Workspace workspace = project();

        PatchApplier.apply(workspace, everyOperation());

        // YAML and JSON: the same parsers that will read them in a generated project.
        assertThat(Documents.readYaml(text(workspace, "src/main/resources/application.yaml")))
                .isNotEmpty();
        assertThat(Documents.readYaml(text(workspace, "compose.yaml"))).containsKey("services");
        assertThat(Documents.readJson(text(workspace, "package.json"))).containsKey("scripts");

        // Maven: a real XML tree, re-read from scratch.
        assertThatXmlParses(text(workspace, "pom.xml"));

        // Gradle: compiling a Kotlin DSL script in a unit test is not something to attempt — that
        // is what the verification matrix is for (§12). What is checked here is the property the
        // editor is responsible for: the dependency landed inside a balanced `dependencies` block.
        String gradle = text(workspace, "build.gradle.kts");
        assertThat(balanced(gradle))
                .as("braces in build.gradle.kts are balanced")
                .isTrue();
        assertThat(dependenciesBlockOf(gradle)).contains("libs.spring.boot.starter.oauth2.resource.server");
    }

    /**
     * A leading dot is dropped on the way to the classpath: Gradle's resource copy inherits Ant's
     * default excludes, which silently swallow {@code .gitignore}. A golden for a dotfile that
     * never arrives is a golden that passes by being absent, so the name is flattened instead.
     */
    private static String goldenName(String path) {
        String flat = path.replace('/', '_');
        return flat.startsWith(".") ? "dot" + flat : flat;
    }

    private static String dependenciesBlockOf(String gradle) {
        // A top-level block starts its own line. The fixture deliberately contains the string
        // literal `"dependencies { not a block }"`, which is what a naive search finds first —
        // and which is exactly why GradleBuildFile scans rather than pattern-matches.
        int start = ("\n" + gradle).indexOf("\ndependencies {");
        assertThat(start).as("build.gradle.kts has a dependencies block").isGreaterThanOrEqualTo(0);
        int depth = 0;
        for (int i = start; i < gradle.length(); i++) {
            char c = gradle.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return gradle.substring(start, i);
                }
            }
        }
        throw new AssertionError("unterminated dependencies block");
    }

    private static boolean balanced(String source) {
        int depth = 0;
        for (char c : source.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static void assertThatXmlParses(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            assertThat(factory.newDocumentBuilder()
                            .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                            .getDocumentElement()
                            .getTagName())
                    .isEqualTo("project");
        } catch (Exception e) {
            throw new AssertionError("pom.xml no longer parses: " + e.getMessage(), e);
        }
    }

    private static String text(Workspace workspace, String path) {
        return new String(workspace.get(path).content(), StandardCharsets.UTF_8);
    }

    private static String resource(String name) {
        try (InputStream in = GoldenPatchTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("test resource %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeGolden(String name, String content) {
        try {
            Path path = Path.of("src/test/resources", name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
