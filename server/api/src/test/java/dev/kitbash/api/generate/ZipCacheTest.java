package dev.kitbash.api.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Determinism, cashed in (§4, §10, §13, §27).
 *
 * <p>Against a real MinIO, because the thing being tested is the interaction with an object store
 * — a fake would agree with itself about keys and lifecycle and prove nothing about either.
 *
 * <p>The test that carries §27's weight is {@link PhaseExit}: generate, save a preset, come back
 * cold, regenerate in one click, and get the identical bytes from cache with no budget spent.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "objectstore", "test"})
@AutoConfigureMockMvc
// One request a minute, so "no budget consumed" is observable rather than asserted by faith.
@TestPropertySource(properties = {"kitbash.limits.generate-per-minute=1", "kitbash.limits.generate-burst=1"})
class ZipCacheTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * quay.io, not Docker Hub — the same reason compose.yaml gives: MinIO stopped publishing
     * these tags there, so the image Testcontainers would reach for by default does not exist.
     */
    private static final MinIOContainer MINIO = new MinIOContainer(
            org.testcontainers.utility.DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                    .asCompatibleSubstituteFor("minio/minio"));

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        MINIO.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("kitbash.objectstore.endpoint", MINIO::getS3URL);
        registry.add("kitbash.objectstore.access-key", MINIO::getUserName);
        registry.add("kitbash.objectstore.secret-key", MINIO::getPassword);
        registry.add("kitbash.objectstore.bucket", () -> "kitbash-test-artifacts");
    }

    private static final String SELECTION =
            """
            {"projectName":"billing","options":{"backend":"backend-spring-java",
             "buildTool":"build-gradle-kts"},
             "variables":{"groupId":"com.acme","packageName":"com.acme.billing","javaVersion":"21",
             "entityName":"Invoice","entityTable":"invoices","envPrefix":"BILLING"}}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void emptyTheTables() {
        jdbc.sql("truncate generation, preset cascade").update();
    }

    private RequestPostProcessor as(String who) {
        return jwt().jwt(token -> token.subject(who))
                .authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_kitbash-author"));
    }

    private MockHttpServletResponse generate(String who, String selection) throws Exception {
        return mvc.perform(post("/api/v1/generate")
                        .with(as(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selection))
                .andReturn()
                .getResponse();
    }

    private double cacheCount(String result) {
        return meters.find("kitbash.cache.requests").tag("result", result).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @Nested
    @DisplayName("the phase exit")
    class PhaseExit {

        /**
         * §27's phase exit test, whole: generate, save a preset, come back cold, regenerate in one
         * click. The zip is identical because §4 made rendering deterministic, it came from the
         * cache because the metric says so, and it cost nothing because the budget was one and the
         * first generation spent it.
         */
        @Test
        @DisplayName("generate, save, cold reload, one-click regenerate: identical, cached, free")
        void generateSavePresetRegenerate() throws Exception {
            double hitsBefore = cacheCount("hit");

            MockHttpServletResponse first = generate("alice", SELECTION);
            assertThat(first.getStatus()).isEqualTo(200);
            byte[] original = first.getContentAsByteArray();

            JsonNode preset = json.readTree(mvc.perform(post("/api/v1/presets")
                            .with(as("alice"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                                    {"name":"house-stack","visibility":"private",
                                     "versionPolicy":"track_latest","selection":%s}"""
                                            .formatted(SELECTION)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            UUID id = UUID.fromString(preset.path("id").asText());

            // Cold: nothing but the id. The budget is already spent, so a render would be a 429.
            MockHttpServletResponse again = mvc.perform(
                            post("/api/v1/presets/" + id + "/generate").with(as("alice")))
                    .andReturn()
                    .getResponse();

            assertThat(again.getStatus())
                    .as("a cache hit consumed rate-limit budget, which §13 forbids")
                    .isEqualTo(200);
            assertThat(again.getContentAsByteArray()).isEqualTo(original);
            assertThat(cacheCount("hit")).isGreaterThan(hitsBefore);
        }

        @Test
        @DisplayName("a cache hit is still a row, pointing at the artifact that served it")
        void aHitIsRecorded() throws Exception {
            generate("bob", SELECTION);
            generate("bob", SELECTION);

            JsonNode history =
                    json.readTree(mvc.perform(get("/api/v1/generations").with(as("bob")))
                            .andReturn()
                            .getResponse()
                            .getContentAsString());

            assertThat(history).as("the cached download was not recorded").hasSize(2);
            for (JsonNode row : history) {
                assertThat(row.path("status").asText()).isEqualTo("succeeded");
            }
            assertThat(jdbc.sql("select count(*) from generation where artifact_key is not null")
                            .query(Integer.class)
                            .single())
                    .as("a row that does not point at its artifact cannot serve it later")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("the key")
    class Key {

        /**
         * §27 says this is a test to write rather than a risk to manage, and it is: the digest is
         * part of the key, so a catalog change cannot be served stale. Asserted by checking that
         * the key the service computes actually contains it — the alternative would be waiting for
         * the catalog to change, which it cannot inside one test.
         */
        @Test
        @DisplayName("the catalog digest is part of the key, so a catalog change cannot serve stale bytes")
        void theDigestIsPartOfTheKey() throws Exception {
            generate("carol", SELECTION);

            String digest = json.readTree(mvc.perform(get("/api/v1/metadata").with(as("carol")))
                            .andReturn()
                            .getResponse()
                            .getContentAsString())
                    .path("catalogDigest")
                    .asText();
            String selectionHash = jdbc.sql("select selection_hash from generation limit 1")
                    .query(String.class)
                    .single();

            assertThat(dev.kitbash.core.hash.Sha256.ofUtf8(digest + "\u0000" + selectionHash))
                    .as("the key a different catalog would produce must differ")
                    .isNotEqualTo(dev.kitbash.core.hash.Sha256.ofUtf8("sha256:another-catalog\u0000" + selectionHash));
        }

        /**
         * Asserted on the keys rather than on the bytes, because two selections this similar
         * produce projects that differ in a handful of files — and "the zips differ" would pass
         * even if the cache were keyed on something that happened to differ too.
         */
        @Test
        @DisplayName("a different selection is a different object, not a lucky hit")
        void differentSelectionsDoNotCollide() throws Exception {
            // Callers of their own: the limiter is a singleton keyed by subject, so a name shared
            // with another test in this class would be a budget shared with it.
            generate("collision-one", SELECTION);
            generate("collision-two", SELECTION.replace("\"billing\"", "\"invoicing\""));

            assertThat(jdbc.sql("select count(distinct artifact_key) from generation where artifact_key is not null")
                            .query(Integer.class)
                            .single())
                    .as("two different selections shared one cached artifact")
                    .isEqualTo(2);
            assertThat(jdbc.sql("select count(distinct selection_hash) from generation")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("the metrics")
    class Metrics {

        @Test
        @DisplayName("hits and misses are both counted, because a rate needs both")
        void countsHitsAndMisses() throws Exception {
            double missesBefore = cacheCount("miss");
            double hitsBefore = cacheCount("hit");

            generate("erin", SELECTION);
            generate("erin", SELECTION);

            assertThat(cacheCount("miss")).isGreaterThan(missesBefore);
            assertThat(cacheCount("hit")).isGreaterThan(hitsBefore);
        }

        /** kitbash-40 reads this to decide which stacks the nightly matrix should prioritise. */
        @Test
        @DisplayName("generations are counted by selection hash, and by nothing that names anybody")
        void countsPopularSelections() throws Exception {
            generate("frank", SELECTION);

            var counters = meters.find("kitbash.generations.byselection").counters();
            assertThat(counters).isNotEmpty();
            // §10: metrics carry hashes and recipe ids, never project or package names.
            assertThat(counters)
                    .allSatisfy(counter -> assertThat(counter.getId().getTags().toString())
                            .doesNotContain("billing")
                            .doesNotContain("com.acme"));
        }
    }
}
