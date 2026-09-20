package dev.kitbash.api.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kitbash.core.selection.Selection;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.core.selection.SelectionValidationException;
import dev.kitbash.core.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Phase0ProjectGeneratorTest {

    private final Phase0ProjectGenerator generator = new Phase0ProjectGenerator();

    @Test
    @DisplayName("source files move to the requested package directory")
    void sourcePathsFollowThePackage() {
        Workspace workspace = generate();

        assertThat(workspace.files().keySet())
                .contains(
                        "src/main/java/com/acme/customer/CustomerManagementApplication.java",
                        "src/main/java/com/acme/customer/domain/Widget.java")
                .noneMatch(path -> path.contains("com/example"));
    }

    @Test
    @DisplayName("the package declaration, the group and the application class all follow")
    void namesAreSubstituted() {
        Workspace workspace = generate();

        assertThat(text(workspace, "src/main/java/com/acme/customer/CustomerManagementApplication.java"))
                .contains("package com.acme.customer;")
                .contains("class CustomerManagementApplication")
                .doesNotContain("com.example")
                .doesNotContain("DemoApplication");

        assertThat(text(workspace, "build.gradle.kts")).contains("group = \"com.acme\"");
        assertThat(text(workspace, "settings.gradle.kts")).contains("rootProject.name = \"customer-management\"");
    }

    @Test
    @DisplayName("DemoProperties follows the application class, not the project name")
    void relatedClassNamesFollowTogether() {
        Workspace workspace = generate();

        assertThat(workspace.contains("src/main/java/com/acme/customer/config/CustomerManagementProperties.java"))
                .isTrue();
        assertThat(text(workspace, "src/main/java/com/acme/customer/CustomerManagementApplication.java"))
                .contains("CustomerManagementProperties");
    }

    @Test
    @DisplayName("the environment prefix follows the project name everywhere it appears")
    void environmentPrefixIsSubstituted() {
        Workspace workspace = generate();

        assertThat(text(workspace, ".env.example"))
                .contains("CUSTOMER_MANAGEMENT_DB_URL")
                .doesNotContain("DEMO_");
        assertThat(text(workspace, "src/main/resources/application.yaml")).contains("${CUSTOMER_MANAGEMENT_DB_URL}");
    }

    @Test
    @DisplayName("the Java version is replaced where it is a version, and nowhere else")
    void javaVersionIsSubstitutedPrecisely() {
        Workspace workspace = generator.generate(
                SelectionEnvelope.current("demo-service", Map.of(), Map.of("javaVersion", "25", "groupId", "com.acme"))
                        .parse());

        assertThat(text(workspace, "build.gradle.kts")).contains("JavaLanguageVersion.of(25)");
        assertThat(text(workspace, "Dockerfile"))
                .contains("temurin:25-jdk-alpine")
                .contains("temurin:25-jre-alpine");
        // The bare string "21" appears in unrelated places (ports, versions); a blanket
        // replacement would corrupt them.
        assertThat(text(workspace, "src/main/resources/application.yaml")).doesNotContain("25432");
    }

    @Test
    @DisplayName("gradlew stays executable and the wrapper jar is left untouched")
    void modesAndBinariesSurvive() {
        Workspace workspace = generate();

        assertThat(workspace.get("gradlew").executable()).isTrue();
        assertThat(workspace.get("build.gradle.kts").executable()).isFalse();
        assertThat(workspace.get("gradle/wrapper/gradle-wrapper.jar").size()).isGreaterThan(10_000);
    }

    @Test
    @DisplayName("the project arrives as a repository with one commit")
    void gitSkeletonIsIncluded() {
        Workspace workspace = generate();

        assertThat(workspace.contains(".git/HEAD")).isTrue();
        assertThat(workspace.contains(".git/index")).isTrue();
        assertThat(text(workspace, ".git/refs/heads/main")).hasSize(41);
    }

    @Test
    @DisplayName("defaults fill in the variables the caller left out")
    void variablesHaveDefaults() {
        Workspace workspace = generator.generate(Selection.of("my-service", Map.of()));

        assertThat(workspace.contains("src/main/java/com/example/myservice/MyServiceApplication.java"))
                .isTrue();
    }

    @Test
    @DisplayName("unknown option keys are carried and ignored, not rejected, in phase 0")
    void unknownOptionsAreIgnored() {
        Selection selection = SelectionEnvelope.current(
                        "my-service", Map.of("somethingFromTheFuture", "yes", "frontend", "react"), Map.of())
                .parse();

        assertThat(generator.generate(selection).fileCount()).isPositive();
    }

    @Test
    @DisplayName("nonsense identifiers are refused before anything is written")
    void refusesBadIdentifiers() {
        assertThatThrownBy(() -> generator.generate(Selection.of("Not A Project", Map.of())))
                .isInstanceOf(SelectionValidationException.class)
                .hasMessageContaining("projectName");

        assertThatThrownBy(() -> generator.generate(Selection.of("ok", Map.of("packageName", "com.new.thing"))))
                .isInstanceOf(SelectionValidationException.class)
                .hasMessageContaining("keyword");

        assertThatThrownBy(() -> generator.generate(Selection.of("ok", Map.of("javaVersion", "8"))))
                .isInstanceOf(SelectionValidationException.class)
                .hasMessageContaining("javaVersion");
    }

    private Workspace generate() {
        return generator.generate(SelectionEnvelope.current(
                        "customer-management",
                        Map.of(),
                        Map.of("groupId", "com.acme", "packageName", "com.acme.customer", "javaVersion", "21"))
                .parse());
    }

    private static String text(Workspace workspace, String path) {
        assertThat(workspace.contains(path))
                .describedAs("expected %s in the generated project", path)
                .isTrue();
        return new String(workspace.get(path).content(), StandardCharsets.UTF_8);
    }
}
