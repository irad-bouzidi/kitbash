package dev.kitbash.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kitbash.catalog.CatalogLoader;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.selection.SelectionEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A feature that was not selected leaves no trace, and a feature that was leaves only its own.
 *
 * <p>§11 defers the monitoring stack and §31 defers the identity provider, and both deferrals are
 * the same promise: selecting a feature adds no container. That promise is easy to keep on the day
 * it is made and easy to break six months later with a compose service somebody thought was
 * harmless — so it is asserted here, byte for byte, rather than remembered.
 *
 * <p>The comparison is of {@code compose.yaml} specifically because that is the file where the
 * breach would show up, and because a diff in it is a diff in what a developer's laptop has to run.
 */
class FeatureIsolationTest {

    private static final CatalogLoader.LoadedCatalog CATALOG = ReferenceProjects.loadCatalog();

    private static String composeOf(Map<String, Object> options) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("groupId", "com.example");
        variables.put("packageName", "com.example.svc");
        variables.put("javaVersion", "21");
        variables.put("entityName", "Widget");
        variables.put("entityTable", "widgets");
        variables.put("envPrefix", "SVC");

        GeneratedProject project =
                ReferenceProjects.pipeline(CATALOG).generate(new SelectionEnvelope(1, "svc", options, variables));
        return new String(project.workspace().get("compose.yaml").content(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> stack(Object... extras) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("buildTool", "build-gradle-kts");
        options.put("backend", "backend-spring-java");
        options.put("architecture", "layered");
        options.put("database", "db-postgres-flyway");
        options.put("docker", true);
        for (int i = 0; i < extras.length; i += 2) {
            options.put((String) extras[i], extras[i + 1]);
        }
        return options;
    }

    @Nested
    @DisplayName("observability")
    class Observability {

        /** §32's own implementation note, as the test it asks for. */
        @Test
        @DisplayName("with tracing off it changes compose.yaml not at all, byte for byte")
        void changesNothingWhenTracingIsOff() {
            assertThat(composeOf(stack("observability", true, "tracing", false)))
                    .as("selecting metrics changed compose.yaml; a Prometheus endpoint is a scrape "
                            + "target, and §11 defers everything that would scrape it")
                    .isEqualTo(composeOf(stack()));
        }

        @Test
        @DisplayName("with tracing on it adds an endpoint to configure, and still no container")
        void addsNoContainerWhenTracingIsOn() {
            String without = composeOf(stack());
            String with = composeOf(stack("observability", true, "tracing", true));

            // Not byte-identical, and should not be: the endpoint belongs on the app service so
            // `docker compose up` works without a .env. What it must not add is a collector.
            assertThat(with).contains("SVC_OTLP_ENDPOINT");
            assertThat(countServices(with))
                    .as("turning tracing on started a container; spans go to a collector the user "
                            + "already runs, not to one this project starts")
                    .isEqualTo(countServices(without));
        }
    }

    @Nested
    @DisplayName("auth")
    class Auth {

        @Test
        @DisplayName("adds environment variables to the app service, and no service of its own")
        void addsNoContainer() {
            String without = composeOf(stack());
            String with = composeOf(stack("auth", true));

            // Not byte-identical, and should not be: auth's variables belong on the app service so
            // `docker compose up` works without a .env. What it must not add is an issuer.
            assertThat(with).contains("SVC_AUTH_ISSUER_URI");
            assertThat(countServices(with))
                    .as("selecting auth started a container; §11 defers the identity provider")
                    .isEqualTo(countServices(without));
        }
    }

    /** Top-level keys under `services:`, which is what "adds a container" means here. */
    private static int countServices(String compose) {
        return (int) compose.lines()
                .filter(line -> line.startsWith("  ")
                        && !line.startsWith("   ")
                        && line.trim().endsWith(":"))
                .count();
    }
}
