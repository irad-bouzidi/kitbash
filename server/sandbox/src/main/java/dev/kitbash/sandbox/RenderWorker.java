package dev.kitbash.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.render.TemplateEngine;
import dev.kitbash.render.TemplateRegistry;
import dev.kitbash.render.TemplateVariables;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The child side of the sandbox: the only code that ever evaluates a contributed template
 * (kitbash-47 §6.4).
 *
 * <p>It is a {@code main} with no Spring context, no data source, no catalog and no logging
 * configuration — not because those were removed, but because nothing here constructs them. The
 * classpath it runs with may well contain a JDBC driver; a driver with no credentials, no
 * connection string and no route off the host is inert, and the network namespace is what makes
 * that last clause true. **The confinement denies the database, not the classpath.** Saying it the
 * other way round would be claiming a control that a packaging change could silently remove.
 *
 * <p>Reads one JSON request from stdin, writes one JSON response to stdout, exits. The process is
 * the unit of isolation, so it renders one recipe and dies rather than looping — a worker that
 * served two requests would be a worker in which the first could leave something for the second.
 */
public final class RenderWorker {

    private RenderWorker() {}

    public static void main(String[] args) {
        // Held before anything else runs. A template cannot reach System.out — there is no method
        // access — but the engine's dependencies might log to it, and a stray line on stdout would
        // corrupt the response the parent is parsing. So the real stdout is captured here and the
        // global one is pointed at stderr, where noise is harmless.
        PrintStream out = System.out;
        System.setOut(System.err);

        ObjectMapper json = new ObjectMapper();
        try {
            SandboxProtocol.Request request = json.readValue(System.in.readAllBytes(), SandboxProtocol.Request.class);

            TemplateEngine engine = TemplateEngine.over(TemplateRegistry.of(request.templates()));

            TemplateVariables.Builder variables = TemplateVariables.builder();
            request.variables().forEach(variables::put);
            TemplateVariables values = variables.build();

            Map<String, String> rendered = new LinkedHashMap<>();
            // Sorted, because the parent compares this output byte for byte against a second run
            // in the determinism check and a hash-ordered map would make that flaky rather than
            // false (§4).
            request.templates().keySet().stream()
                    .sorted()
                    .forEach(name -> rendered.put(name, engine.render(name, request.recipeId(), values)));

            write(out, json, new SandboxProtocol.Response(rendered, null));
        } catch (Exception failed) {
            // Every failure is a message, never a stack trace: §14 says no stack trace reaches a
            // user, and this one would cross a process boundary into an API response. The class
            // name is included because "null" on its own has wasted an afternoon before.
            write(
                    out,
                    json,
                    new SandboxProtocol.Response(
                            null,
                            failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()));
            System.exit(1);
        }
    }

    private static void write(PrintStream out, ObjectMapper json, SandboxProtocol.Response response) {
        try {
            out.write(json.writeValueAsBytes(response));
            out.flush();
        } catch (Exception unwritable) {
            // The parent is reading a pipe; if it cannot be written to, there is nobody to tell.
            // The exit code is the whole message.
            System.exit(2);
        }
    }
}
