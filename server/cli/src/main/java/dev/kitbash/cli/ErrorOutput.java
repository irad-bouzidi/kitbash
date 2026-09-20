package dev.kitbash.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.kitbash.core.error.GenerationError;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Failures as the §14 envelope, printed as JSON to stderr.
 *
 * <p>The task asks for it parseable because CI is the caller that matters: a matrix cell that
 * fails should report the code, the stage, the recipe and the file in a form a job can read,
 * rather than a sentence a human has to grep. The same envelope goes to stdout for a human when
 * the failure is not the selection's fault.
 */
final class ErrorOutput {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErrorOutput() {}

    static int print(GenerationError error, PrintStream err) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", error.code().name());
        envelope.put("stage", error.stage().wireName());
        if (error.recipe() != null) {
            envelope.put("recipe", error.recipe());
        }
        if (error.file() != null) {
            envelope.put("file", error.file());
        }
        envelope.put("message", error.message());
        envelope.put("hint", error.hint());
        if (error.selectionHash() != null) {
            envelope.put("selectionHash", error.selectionHash());
        }

        ObjectNode node = JSON.valueToTree(envelope);
        err.println(node.toPrettyString());
        return 1;
    }

    /** A failure that is not the selection's fault: a missing file, an unreadable catalog. */
    static int print(String message, PrintStream err) {
        err.println(message);
        return 1;
    }
}
