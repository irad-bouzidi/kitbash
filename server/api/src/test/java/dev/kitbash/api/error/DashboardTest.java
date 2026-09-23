package dev.kitbash.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The dashboard queries metrics this service actually emits (§41).
 *
 * <p>§41's "done when" is that <i>every metric in the §14 list is emitted and visible on the
 * dashboard</i>, and the failure mode is quiet in both directions. A panel querying a metric nobody
 * registers reads "No data" during the one incident it was kept for, and nothing about the JSON
 * looks wrong until then. A metric emitted and never put on a panel is a number nobody sees.
 *
 * <p>So this reads the checked-in dashboard, pulls the metric names out of its PromQL, and checks
 * them against the registry after exercising the endpoints that populate it.
 */
@SpringBootTest
// With persistence, because some of the metrics only exist where the feature does. Verification is
// a row, so a deployment without a database has no queue and no runs — and a dashboard asserted
// against a context missing them would be asserted against a smaller dashboard than ships.
@ActiveProfiles({"persistence", "test"})
@AutoConfigureMockMvc
class DashboardTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A Prometheus metric name in an expression: the identifier before `{`, `[` or an operator. */
    private static final Pattern METRIC = Pattern.compile("\\bkitbash_[a-z_]+\\b");

    private static final String SELECTION =
            """
            {"projectName":"dashboard","options":{"backend":"backend-spring-java",
             "buildTool":"build-gradle-kts","database":"db-postgres-flyway"},
             "variables":{"groupId":"com.example","packageName":"com.example.dashboard",
             "javaVersion":"21","entityName":"Widget","entityTable":"widgets","envPrefix":"DASH"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meters;

    private static Path dashboard() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("docs"))) {
            candidate = candidate.getParent();
        }
        return candidate.resolve("docs").resolve("dashboard.json");
    }

    @Test
    @DisplayName("every metric the dashboard queries is one this service registers")
    void everyPanelHasItsMetric() throws Exception {
        // Exercised first: a registry is empty until something has happened, and a test that
        // checked an empty one would pass by asserting nothing.
        mvc.perform(post("/api/v1/generate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn();
        mvc.perform(post("/api/v1/generate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION.replace("backend-spring-java", "backend-spring-jav")))
                .andReturn();
        mvc.perform(get("/api/v1/verification").with(jwt())).andReturn();

        Set<String> registered = new LinkedHashSet<>();
        for (Meter meter : meters.getMeters()) {
            // Micrometer's name, as Prometheus renders it: dots become underscores, and a counter
            // or timer gains a suffix the expression will carry.
            registered.add(meter.getId().getName().replace('.', '_'));
        }

        for (String queried : queriedMetrics()) {
            assertThat(registered)
                    .as(
                            "the dashboard queries %s, which nothing registers — a panel reading "
                                    + "'No data' during an incident is worse than no panel, because it "
                                    + "looks like the thing it measures is quiet",
                            queried)
                    .anySatisfy(name -> assertThat(queried).startsWith(name));
        }
    }

    @Test
    @DisplayName("every metric in the §14 list has a panel, so nothing is emitted unseen")
    void everyMetricHasItsPanel() throws Exception {
        List<String> required = List.of(
                "kitbash_generations", // duration and outcome
                "kitbash_cache_requests", // cache hit rate
                "kitbash_recipes_used", // recipe usage
                "kitbash_rejections", // validation failure reasons by type (§39)
                "kitbash_matrix_passed", // matrix pass rate
                "kitbash_verify_queue_depth", // verification queue depth (§37)
                "kitbash_verify_runs"); // verification run outcomes

        Set<String> queried = queriedMetrics();

        for (String metric : required) {
            assertThat(queried)
                    .as(
                            "§14 lists %s and §41 asks for it to be visible; a metric on no panel is a "
                                    + "number nobody sees",
                            metric)
                    .anySatisfy(expression -> assertThat(expression).startsWith(metric));
        }
    }

    @Test
    @DisplayName("no panel queries anything tagged by selection hash")
    void noPanelAsksForACardinalityProblem() throws Exception {
        // §41: anything tagged by selection hash is not a metric, it is a log line. A dashboard
        // asking for one is how the tag comes back.
        assertThat(Files.readString(dashboard())).doesNotContain("selection=").doesNotContain("by (selection)");
    }

    private Set<String> queriedMetrics() throws Exception {
        JsonNode document = JSON.readTree(dashboard().toFile());
        Set<String> metrics = new LinkedHashSet<>();
        for (JsonNode panel : document.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                Matcher matcher = METRIC.matcher(target.path("expr").asText());
                while (matcher.find()) {
                    metrics.add(matcher.group());
                }
            }
        }
        assertThat(metrics)
                .as("a dashboard querying nothing would pass every assertion below")
                .isNotEmpty();
        return metrics;
    }
}
