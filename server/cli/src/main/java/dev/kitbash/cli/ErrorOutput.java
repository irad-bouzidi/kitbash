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
        return print(error, err, System.console() != null);
    }

    /**
     * @param forAPerson whether to put a readable line above the envelope (§42). True when stdout
     *     is a terminal — a person is reading — and false when it is a pipe, because the caller is
     *     then a script and a line of prose before the JSON is a line it has to skip.
     *     <p>Java 21 has no direct "is this a TTY". {@code System.console()} is the available
     *     signal and it is not exact: it answers for the pair of streams rather than for stderr
     *     alone, so `kitbash generate > file` in a terminal still gets the human line although
     *     stdout is redirected. The envelope is always present and always last, so the worst case
     *     is a line of prose nobody wanted rather than output a script cannot parse.
     */
    static int print(GenerationError error, PrintStream err, boolean forAPerson) {
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

        if (forAPerson) {
            // The message and the next action, in that order, because §14 puts the next action in
            // the hint and a person reading a failure wants it before they want the structure.
            err.printf("%s: %s%n", error.code().name(), error.message());
            err.printf("  %s%n", error.hint());
            err.println();
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
