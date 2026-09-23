package dev.kitbash.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The one generic message §39 allows, and the conditions it is allowed under.
 *
 * <p>§39: <i>no generic "something went wrong" fallback except for genuinely unmapped failures —
 * and those are logged as a defect.</i> Both halves are asserted here, because each without the
 * other is worse than neither.
 *
 * <p>Without the handler, an {@code IllegalStateException} from inside the patch stage would reach
 * a user as its own text: {@code server.error.include-message: always} is set, which is right for
 * the typed errors and is a slow leak of internals for everything else.
 *
 * <p>Without the log, the handler would be where failures go to be forgotten. Every hit is a throw
 * site that has no error type yet — a defect in this codebase, not a mistake by the caller — and
 * the reply has to say so rather than implying the request was wrong.
 */
class UnmappedFailureTest {

    private final ApiExceptionHandler handler =
            new ApiExceptionHandler(new RejectionMetrics(new SimpleMeterRegistry()));

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    private Logger logger;

    @BeforeEach
    void captureTheLog() {
        logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void releaseTheLog() {
        logger.detachAppender(captured);
    }

    @Test
    @DisplayName("an unmapped failure is a 500 in the §14 shape, with a reference and no internals")
    void answersInTheEnvelopeShape() {
        ProblemDetail problem = handler.unmapped(new IllegalStateException("Could not write pom.xml from memory"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getProperties())
                .containsKey("error")
                .containsKey("hint")
                .containsKey("reference");
        assertThat(problem.getProperties().get("error")).isEqualTo("UNEXPECTED");

        // The message the exception carried is the part that must not travel. It names a file
        // nobody asked about and a failure nobody can act on, and it is exactly what
        // `include-message: always` would have sent without this handler.
        String body = problem.getDetail() + " " + problem.getProperties().get("hint");
        assertThat(body).doesNotContain("pom.xml").doesNotContain("IllegalStateException");
    }

    @Test
    @DisplayName("the hint says the selection is not the problem, because it is not")
    void doesNotBlameTheCaller() {
        ProblemDetail problem = handler.unmapped(new IllegalStateException("something internal"));

        // A generic 500 that says "check your selection" sends somebody to edit a request that was
        // fine. The one useful action is quoting the reference, so that is what it names.
        assertThat(problem.getProperties().get("hint").toString())
                .contains("Nothing about the selection needs changing")
                .contains(problem.getProperties().get("reference").toString());
    }

    @Test
    @DisplayName("every unmapped failure is logged at error with its cause and its reference")
    void logsADefect() {
        IllegalStateException cause = new IllegalStateException("the cause nobody mapped");

        ProblemDetail problem = handler.unmapped(cause);

        assertThat(captured.list).hasSize(1);
        ILoggingEvent event = captured.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .as("a fallback nobody is told about is a fallback failures go to be forgotten in")
                .contains(problem.getProperties().get("reference").toString())
                .contains("defect");
        assertThat(event.getThrowableProxy())
                .as("without the cause the log line says only that something unmapped happened")
                .isNotNull();
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("the cause nobody mapped");
    }

    @Test
    @DisplayName("two failures get two references, so a bug report names one of them")
    void referencesAreDistinct() {
        Object first =
                handler.unmapped(new IllegalStateException("a")).getProperties().get("reference");
        Object second =
                handler.unmapped(new IllegalStateException("b")).getProperties().get("reference");

        assertThat(first).isNotEqualTo(second);
    }
}
