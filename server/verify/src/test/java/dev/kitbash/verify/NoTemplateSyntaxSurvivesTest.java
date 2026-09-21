package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.selection.SelectionEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No generated project contains a Pebble tag.
 *
 * <p>This exists because one did. §28 put
 * <code>{% if capabilities contains 'maven-build' %}…{% endif %}</code> in a README patch line, and
 * the renderer skipped the engine for any string without <code>{{</code> — so the tag shipped
 * verbatim to every user who declined containers. Every cell stayed green, because a cell builds a
 * project and nobody reads its README.
 *
 * <p>So the check is not "the renderer works" — it is "nothing that looks like a template reached
 * the output", asserted over combinations rather than over one. The failure it catches next will
 * not be this one.
 */
class NoTemplateSyntaxSurvivesTest {

    private static final CatalogLoader.LoadedCatalog CATALOG = ReferenceProjects.loadCatalog();

    /** Enough of the option space that a conditional only some selections reach is still rendered. */
    private static List<Map<String, Object>> selections() {
        List<Map<String, Object>> selections = new ArrayList<>();
        for (String backend : List.of("backend-spring-java", "backend-spring-kotlin")) {
            for (String buildTool : List.of("build-gradle-kts", "build-maven")) {
                for (boolean docker : List.of(true, false)) {
                    for (boolean extras : List.of(true, false)) {
                        Map<String, Object> options = new LinkedHashMap<>();
                        options.put("buildTool", buildTool);
                        options.put("backend", backend);
                        options.put("architecture", "layered");
                        options.put("database", "db-postgres-flyway");
                        options.put("frontend", "frontend-react-vite");
                        options.put("ci", "ci-gitlab");
                        options.put("docker", docker);
                        options.put("auth", extras);
                        options.put("observability", extras);
                        options.put("tracing", extras);
                        options.put("typedClient", extras);
                        selections.add(options);
                    }
                }
            }
        }
        return selections;
    }

    /** Detected by content rather than extension, as the packager does. */
    private static boolean isBinary(byte[] content) {
        int inspected = Math.min(content.length, 8_000);
        for (int i = 0; i < inspected; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("no file in any generated project still contains a Pebble tag")
    void nothingUnrendered() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("groupId", "com.example");
        variables.put("packageName", "com.example.svc");
        variables.put("javaVersion", "21");
        variables.put("entityName", "Widget");
        variables.put("entityTable", "widgets");
        variables.put("envPrefix", "SVC");

        List<String> found = new ArrayList<>();
        for (Map<String, Object> options : selections()) {
            GeneratedProject project =
                    ReferenceProjects.pipeline(CATALOG).generate(new SelectionEnvelope(1, "svc", options, variables));
            project.workspace().files().forEach((path, file) -> {
                // The git skeleton's objects are zlib, and a compressed byte pair is not a tag.
                if (path.startsWith(".git/") || isBinary(file.content())) {
                    return;
                }
                String text = new String(file.content(), StandardCharsets.UTF_8);
                // `{{` appears legitimately in a generated GitLab pipeline's own expressions and in
                // nothing else here, so the tag that matters is Pebble's statement form.
                if (text.contains("{%")) {
                    found.add(path + " in " + options);
                }
            });
        }

        assertThat(found)
                .as("a Pebble tag reached the output. A patch line or template is not being "
                        + "rendered, and the project a user unzips says {%% if ... %%} to them.")
                .isEmpty();
    }
}
