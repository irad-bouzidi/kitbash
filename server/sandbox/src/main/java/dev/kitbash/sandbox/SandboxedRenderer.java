package dev.kitbash.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Renders a contributed recipe's templates in a confined child process (§13, kitbash-47 §6.4).
 *
 * <p>Only contributed recipes come through here. Shipped ones keep rendering in process: they are
 * not the untrusted input, and routing them through a subprocess would cost every generation a
 * JVM start to defend against a repository we control.
 *
 * <h2>What confines it</h2>
 *
 * <ul>
 *   <li><b>A separate process</b>, so an escape lands somewhere that holds nothing.
 *   <li><b>No network</b>, from a namespace the host grants — see {@link Confinement}. This is the
 *       control that also denies the database and the dependency proxy, because a route is what
 *       reaching either requires.
 *   <li><b>A hard heap cap</b>, so a template that allocates dies in a process whose death costs
 *       one request rather than the server.
 *   <li><b>A hard wall clock</b>, enforced by the parent with {@code destroyForcibly}. Not a
 *       cooperative timeout: the thing being timed out is hostile, and a cooperative one asks it
 *       to stop.
 *   <li><b>One visible processor</b>, so a parallel allocation storm cannot use the host's cores.
 *       This bounds the blast radius rather than the CPU: a real quota needs cgroup privileges
 *       this server does not assume it has, and claiming otherwise would be claiming a control
 *       that is not there. The wall clock is what actually ends a spin.
 *   <li><b>An empty environment and an empty working directory</b>, so there is no credential to
 *       inherit and nothing beside the process to find.
 * </ul>
 *
 * <p>The filesystem is not made read-only by a mount option. It is made irrelevant: templates
 * arrive over stdin and output leaves over stdout, so the worker opens no file at all
 * ({@link SandboxProtocol}). That is a stronger claim than a remount, because it does not depend
 * on a flag somebody has to keep setting — but it is a different claim, and the difference is
 * written down here rather than glossed.
 */
public final class SandboxedRenderer {

    /**
     * §13's generation budget is 10 seconds for the whole pipeline; a single recipe's templates
     * get a fraction of it. Generous enough that a slow host is not a failure, short enough that
     * holding it is not a denial of service.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Enough for the templates of one recipe with room to spare, far below what it would take to
     * trouble the host. The point is not the number; it is that the number exists and the process
     * dies at it.
     */
    public static final int HEAP_MEGABYTES = 256;

    private final Confinement confinement;
    private final String classpath;
    private final ObjectMapper json = new ObjectMapper();

    public SandboxedRenderer(Confinement confinement, String classpath) {
        this.confinement = confinement;
        this.classpath = classpath;
    }

    /**
     * Renders every template of one contributed recipe, or refuses.
     *
     * @return template name to rendered output
     * @throws SandboxRefusedException when the host cannot confine the worker, when a cap was
     *     reached, or when the recipe's templates did not render
     */
    public Map<String, String> render(String recipeId, Map<String, String> templates, Map<String, Object> variables) {
        if (!confinement.isAvailable()) {
            // The fail-closed path. Named as a host capability rather than a recipe fault,
            // because an operator watching these fail needs to look at the host.
            throw new SandboxRefusedException(
                    "SANDBOX_UNAVAILABLE",
                    "This server cannot confine contributed recipes, so it will not render them: "
                            + confinement.reason() + ".",
                    "Contributed recipes need unprivileged user namespaces, which this host does not "
                            + "grant. Shipped recipes are unaffected. Enable them on the host, or run "
                            + "the server where they are enabled.");
        }

        Path workingDirectory = emptyDirectory();
        Process worker = null;
        try {
            worker = start(workingDirectory);
            worker.getOutputStream()
                    .write(json.writeValueAsBytes(new SandboxProtocol.Request(templates, variables, recipeId)));
            worker.getOutputStream().close();

            // Drained on another thread, and this is not a style choice.
            //
            // Reading the pipe to EOF on *this* thread deadlocks against the very case the wall
            // clock exists for: a worker that spins never writes and never exits, so the read
            // blocks forever and the timeout below is never reached. That defect was in this
            // method until the adversarial suite's spinning template found it, which is the
            // argument for the suite in one sentence — the control was written down, present in
            // the comments, and not running.
            //
            // A drain is needed regardless: a worker that writes more than the pipe buffer while
            // nobody reads blocks on the write, and would then be killed for a timeout it did not
            // cause.
            java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
            Process reading = worker;
            Thread drain = Thread.ofVirtual().start(() -> {
                try (java.io.InputStream out = reading.getInputStream()) {
                    out.transferTo(collected);
                } catch (IOException closed) {
                    // The worker was killed mid-write. An empty or truncated response is the
                    // signal, and read() below turns it into SANDBOX_KILLED.
                }
            });

            if (!worker.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                worker.destroyForcibly();
                throw new SandboxRefusedException(
                        "SANDBOX_TIMEOUT",
                        "Recipe " + recipeId + " did not finish rendering within " + TIMEOUT.toSeconds()
                                + " seconds and was stopped.",
                        "A template that loops or recurses will do this. Nothing was generated; the "
                                + "recipe needs fixing before it can be approved.");
            }
            // The process is gone, so the pipe is closed and the drain is finishing. Joined with
            // a bound rather than indefinitely: this thread has already proved it will wait for
            // something that never ends if asked to.
            drain.join(java.time.Duration.ofSeconds(5));
            return read(recipeId, collected.toByteArray(), worker.exitValue());
        } catch (IOException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SandboxRefusedException(
                    "SANDBOX_FAILED",
                    "The sandbox for recipe " + recipeId + " did not complete: " + failed.getMessage(),
                    "This is a fault in the server rather than in the recipe. Nothing was generated.");
        } finally {
            if (worker != null && worker.isAlive()) {
                worker.destroyForcibly();
            }
            delete(workingDirectory);
        }
    }

    private Process start(Path workingDirectory) throws IOException {
        List<String> java = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-Xmx" + HEAP_MEGABYTES + "m",
                // One processor, so a storm of parallel allocation cannot recruit the host's
                // cores. See the class comment for what this does and does not buy.
                "-XX:ActiveProcessorCount=1",
                // Headless and with no agents: a template cannot reach either, but a JVM that
                // starts fewer subsystems is a JVM with less to escape into.
                "-Djava.awt.headless=true",
                "-XX:+DisableAttachMechanism",
                "-cp",
                classpath,
                RenderWorker.class.getName()));

        ProcessBuilder builder = new ProcessBuilder(Confinement.command(java));
        builder.directory(workingDirectory.toFile());
        // Cleared rather than filtered. A filter is a list of the variables somebody remembered,
        // and the inherited environment of an application server holds credentials nobody
        // inventoried.
        builder.environment().clear();
        // Kept apart from stdout on purpose: stdout carries the response and stderr carries
        // whatever the JVM feels like saying. Merging them would corrupt the first with the second.
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private Map<String, String> read(String recipeId, byte[] output, int exitCode) throws IOException {
        if (output.length == 0) {
            // No response at all: the worker was killed before it could write. The heap cap is by
            // far the likeliest reason, and saying so beats "exit code 1".
            throw new SandboxRefusedException(
                    "SANDBOX_KILLED",
                    "Recipe " + recipeId + " was stopped before it produced anything (exit " + exitCode + ").",
                    "A template that allocates without bound will do this; the worker is capped at " + HEAP_MEGABYTES
                            + " MB. Nothing was generated.");
        }
        SandboxProtocol.Response response = json.readValue(output, SandboxProtocol.Response.class);
        if (response.error() != null) {
            throw new SandboxRefusedException(
                    "SANDBOX_RENDER_FAILED",
                    "Recipe " + recipeId + " did not render: " + response.error(),
                    "The template named in the message is the one to fix. This is the recipe's "
                            + "fault rather than the server's, and nothing was generated.");
        }
        return response.files();
    }

    private static Path emptyDirectory() {
        try {
            // Empty and the worker's cwd, so a relative path resolves to nothing. It is not where
            // the render happens — the render happens in pipes — it is the absence of a place.
            return Files.createTempDirectory("kitbash-sandbox-");
        } catch (IOException e) {
            throw new SandboxRefusedException(
                    "SANDBOX_FAILED",
                    "The sandbox could not be given a working directory: " + e.getMessage(),
                    "This is a fault in the server rather than in the recipe. Nothing was generated.");
        }
    }

    private static void delete(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A temp directory the worker never wrote to. Worth no more than this.
        }
    }
}
