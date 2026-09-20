package dev.kitbash.api.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A cache hit costs no budget (§13).
 *
 * <p>The task that specified this called it the requirement most likely to regress silently, and
 * it is: nothing about a rate limiter looks wrong when it is a servlet filter, and a filter runs
 * before the handler has had any chance to find a cached answer. The symptom would be a user who
 * downloads the same project five times in a minute being told to slow down — which is exactly the
 * usage this product most wants to encourage, since serving bytes that already exist costs
 * nothing worth metering.
 *
 * <p>So the limit here is set to <b>one</b> request a minute, and then the same generation is
 * asked for five times. The first renders and spends the budget; the rest come back from the cache
 * and must still be served.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "kitbash.limits.generate-per-minute=1",
            "kitbash.limits.generate-burst=1",
        })
class CacheHitsAreFreeTest {

    private static final String SELECTION =
            """
            {"projectName":"demo","options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts"},
             "variables":{"groupId":"com.example","packageName":"com.example.demo","javaVersion":"21",
             "entityName":"Widget","entityTable":"widgets","envPrefix":"DEMO"}}""";

    /**
     * A cache that remembers what it is given. {@code kitbash-27} brings the real one; what this
     * test needs is only that a hit is possible, so that the ordering can be observed.
     */
    @TestConfiguration
    static class RememberingCache {

        static final Map<String, byte[]> ENTRIES = new HashMap<>();
        static final AtomicInteger HITS = new AtomicInteger();

        @Bean
        @Primary
        ZipCache cache() {
            return new ZipCache() {
                @Override
                public Optional<byte[]> find(String key) {
                    Optional<byte[]> found = Optional.ofNullable(ENTRIES.get(key));
                    found.ifPresent(ignored -> HITS.incrementAndGet());
                    return found;
                }

                @Override
                public void put(String key, byte[] zip) {
                    ENTRIES.put(key, zip);
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    /**
     * A caller of its own per test.
     *
     * <p>The limiter is a singleton and its buckets are keyed by subject, so two tests sharing a
     * subject would share a budget — and the second would fail for a reason that has nothing to do
     * with what it is asserting. Real callers are distinct; these are too.
     */
    private String caller;

    @BeforeEach
    void emptyTheCache(org.junit.jupiter.api.TestInfo test) {
        RememberingCache.ENTRIES.clear();
        RememberingCache.HITS.set(0);
        caller = test.getTestMethod().orElseThrow().getName();
    }

    @Test
    @DisplayName("the same generation five times over a budget of one: the first renders, the rest are free")
    void repeatedIdenticalGenerationsAreFree() throws Exception {
        byte[] first = generate(200);
        assertThat(first).isNotEmpty();

        // Without a cache, a second request against a budget of one is a 429 — which is what the
        // next test asserts, so this one is not passing because the limiter is asleep.
        for (int repeat = 0; repeat < 4; repeat++) {
            assertThat(generate(200))
                    .as("repeat %d was refused, so a cache hit is costing budget", repeat + 1)
                    .isEqualTo(first);
        }

        assertThat(RememberingCache.HITS.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("and the limiter really is on: a second *different* generation is a 429")
    void theLimiterStillEngages() throws Exception {
        generate(200);

        // A different project name is a different selection, so a different cache key: nothing to
        // serve, and the budget is gone.
        assertThat(mvc.perform(post("/api/v1/generate")
                                .with(signedIn())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(SELECTION.replace("\"demo\"", "\"other-demo\"")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("a 429 carries the envelope and a Retry-After, so a client knows when")
    void refusalSaysWhen() throws Exception {
        generate(200);

        var response = mvc.perform(post("/api/v1/generate")
                        .with(signedIn())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION.replace("\"demo\"", "\"another-demo\"")))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString())
                .contains("RATE_LIMITED")
                .contains("60 seconds")
                // The hint says what did not cause this, because the obvious guess is wrong.
                .contains("already served from cache");
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor signedIn() {
        return jwt().jwt(token -> token.subject(caller));
    }

    private byte[] generate(int expectedStatus) throws Exception {
        var response = mvc.perform(post("/api/v1/generate")
                        .with(signedIn())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        return response.getContentAsByteArray();
    }
}
