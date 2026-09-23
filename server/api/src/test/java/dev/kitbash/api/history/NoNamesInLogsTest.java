package dev.kitbash.api.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Names stay out of the logs (§10, §24).
 *
 * <p>§10 is unambiguous: history keeps the project name because users need to recognise their own
 * rows, and deletes it with the record — while logs and metrics carry hashes and recipe ids only.
 * The reason is that the two have different lifetimes. A row is deleted when its owner deletes it
 * or when the sweep takes it; a log line is shipped to somewhere else, kept on somebody else's
 * retention schedule, and is not deleted by anything this service does.
 *
 * <p>So the rule needs a test, because it is the kind of rule that is broken by one helpful
 * {@code log.info("Generating {}", projectName)} added during a debugging session.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NoNamesInLogsTest {

    /** Distinctive enough that a substring match is meaningful, valid enough to be accepted. */
    private static final String PROJECT_NAME = "zarquon-ledger";

    private static final String PACKAGE_NAME = "com.zarquon.ledger";

    private static final String SELECTION =
            """
            {"projectName":"%s","options":{"backend":"backend-spring-java","buildTool":"build-gradle-kts"},
             "variables":{"groupId":"com.zarquon","packageName":"%s","javaVersion":"21",
             "entityName":"Ledger","entityTable":"ledgers","envPrefix":"ZARQUON"}}"""
                    .formatted(PROJECT_NAME, PACKAGE_NAME);

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger root;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeEach
    void captureEverything() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.TRACE);
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("a successful generation logs hashes and recipe ids, never the names it was given")
    void successLogsNoNames() throws Exception {
        mvc.perform(post("/api/v1/generate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn();

        assertNoNamesIn(rendered());
        // And it is not silent, which would make the assertion above pass for the wrong reason.
        assertThat(rendered()).anyMatch(line -> line.contains("Generated selection="));
    }

    @Test
    @DisplayName("a failed generation logs no names either, which is when the temptation is highest")
    void failureLogsNoNames() throws Exception {
        mvc.perform(post("/api/v1/generate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION.replace("backend-spring-java", "backend-spring-jva")))
                .andReturn();

        assertNoNamesIn(rendered());
    }

    @Test
    @DisplayName("validating logs no names, though it sees the same envelope")
    void validateLogsNoNames() throws Exception {
        mvc.perform(post("/api/v1/validate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn();
        mvc.perform(get("/api/v1/metadata").with(jwt())).andReturn();

        assertNoNamesIn(rendered());
    }

    /**
     * The lines this rule can honestly cover.
     *
     * <p>Two kinds: anything this repository's own code logged, at any level, and anything at all
     * logged at {@code DEBUG} or above.
     *
     * <p>The exclusion is Spring MVC at {@code TRACE}, which prints every deserialised request body
     * — so it prints the selection, names and all. That is a framework's debugging mode rather than
     * a leak this code can fix, no deployment runs it, and asserting against it would mean either
     * a failing test nobody can act on or deleting the level. What §10 is actually about is the
     * lines a running service ships, and the regression it guards against is one helpful
     * {@code log.info("Generating {}", projectName)} added during a debugging session — which this
     * catches at any level, because the first filter has no level bound.
     */
    private List<String> rendered() {
        return captured.list.stream()
                .filter(event -> event.getLoggerName().startsWith("dev.kitbash")
                        || event.getLevel().isGreaterOrEqual(Level.DEBUG))
                .map(event -> event.getFormattedMessage() + " " + java.util.Arrays.toString(event.getArgumentArray()))
                .toList();
    }

    private static void assertNoNamesIn(List<String> lines) {
        assertThat(lines.stream()
                        .filter(line -> line.contains(PROJECT_NAME)
                                || line.contains(PACKAGE_NAME)
                                || line.contains("com.zarquon"))
                        .toList())
                .as("§10: logs carry hashes and recipe ids only. A project or package name in a log "
                        + "line outlives the row it came from, because a log is shipped elsewhere and "
                        + "deleted by nothing this service does.")
                .isEmpty();
    }

    /**
     * The other half of §10's rule, and the one §41 names explicitly.
     *
     * <p>A metric is worse than a log line for this. A line is written once and ages out; a tag
     * becomes a <b>time series</b>, which persists as a dimension in somebody else's monitoring
     * system long after every request that created it is forgotten, and is queried by people who
     * never saw the request.
     */
    @Test
    @DisplayName("no metric anywhere carries a name, in its own name or in a tag")
    void noNamesInMetrics() throws Exception {
        mvc.perform(post("/api/v1/generate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SELECTION))
                .andReturn();

        List<String> offending = meters.getMeters().stream()
                .map(meter ->
                        meter.getId().getName() + " " + meter.getId().getTags().toString())
                .filter(descriptor -> descriptor.contains(PROJECT_NAME)
                        || descriptor.contains(PACKAGE_NAME)
                        || descriptor.contains("zarquon"))
                .toList();

        assertThat(offending)
                .as("§10: metrics carry hashes and recipe ids only. A name in a tag is not a line "
                        + "that ages out — it is a time series, kept on somebody else's retention "
                        + "schedule and queried by people who never saw the request. §41 adds the "
                        + "cardinality half: a tag whose values come from user input is a series "
                        + "count nobody chose.")
                .isEmpty();
    }

    @Test
    @DisplayName("a request can be traced by an id it is given, and the id cannot forge a log line")
    void correlationIdIsEchoedAndSanitised() throws Exception {
        // Honoured, so this traces alongside anything already tracing in front of it.
        assertThat(mvc.perform(get("/api/v1/metadata").with(jwt()).header("X-Correlation-Id", "abc-123"))
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Correlation-Id"))
                .isEqualTo("abc-123");

        // Replaced, not rejected: the request is fine, its id is not. An id reaching every log
        // line is caller-controlled input going somewhere grepped, shipped and kept.
        //
        // A space and a quote rather than a newline, deliberately. A newline is the attack worth
        // preventing — it writes log lines that never happened — but MockMvc does not deliver one,
        // so asserting against it proved nothing: the filter was replaced with one that accepts
        // anything and this test stayed green. These characters survive the transport and are
        // refused by the same rule.
        assertThat(correlationIdFor("has spaces and \"quotes\""))
                .as("an id is echoed into every log line, so it is not free-form caller input")
                .satisfies(id -> assertThat(java.util.UUID.fromString(id)).isNotNull());

        assertThat(correlationIdFor("x".repeat(500)))
                .as("a megabyte of id is a megabyte on every line of the request")
                .hasSize(36);
    }

    /** The id the server settled on for a request carrying this one. */
    private String correlationIdFor(String offered) throws Exception {
        return mvc.perform(get("/api/v1/metadata").with(jwt()).header("X-Correlation-Id", offered))
                .andReturn()
                .getResponse()
                .getHeader("X-Correlation-Id");
    }
}
