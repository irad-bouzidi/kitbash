package dev.kitbash.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code docker run} for one step.
 *
 * <p>Every limit here is §12/§13 rather than taste. A generated build is untrusted input the
 * moment recipes are contributed, and a cell that can eat the runner takes the matrix with it when
 * one recipe misbehaves: CPU and memory caps with swap pinned to the same value, a pid limit, no
 * new privileges, a non-root user inside, a read-only mount of the project, and a hard timeout.
 *
 * <p>The network is still the default bridge. §12 asks for "no network beyond the dependency
 * proxy" and there is no proxy yet — that gap is named in {@code verification/README.md} rather
 * than hidden, because a warm cache pretending to be isolation is worse than neither.
 */
public record Containers(Map<String, String> images, String cpus, String memory, int timeoutSeconds) {

    public static final Map<String, String> DEFAULT_IMAGES = Map.of(
            "jvm", "kitbash/verify-jvm:latest",
            "node", "kitbash/verify-node:latest",
            "ci", "kitbash/verify-ci:latest");

    /**
     * Where each image puts the writable copy of the project.
     *
     * <p>They differ, and that is fine until a cell shares one volume between them — so the mount
     * point is per ecosystem and the volume is not. Getting this wrong would mount the JVM's tree
     * at a path the Node image never looks at, and the second step would quietly build the
     * original project.
     */
    private static final Map<String, String> WORKSPACES = Map.of("jvm", "/workspace", "node", "/work", "ci", "/work");

    public static Containers standard() {
        return new Containers(
                Map.of(
                        "jvm", env("KITBASH_JVM_IMAGE", DEFAULT_IMAGES.get("jvm")),
                        "node", env("KITBASH_NODE_IMAGE", DEFAULT_IMAGES.get("node")),
                        "ci", env("KITBASH_CI_IMAGE", DEFAULT_IMAGES.get("ci"))),
                env("KITBASH_CELL_CPUS", "2"),
                env("KITBASH_CELL_MEMORY", "4g"),
                Integer.parseInt(env("KITBASH_CELL_TIMEOUT", "900")));
    }

    /**
     * The marker a step's script prints when one of its commands fails.
     *
     * <p>The container is a separate process whose only channel back is the log, so this is how the
     * runner learns <i>which</i> command failed rather than only that the step did.
     */
    public static final String FAILED = "[step-failed] ";

    public List<String> commandFor(Cell.Step step, Path project) {
        return commandFor(step, project, null);
    }

    /**
     * @param workspaceVolume a Docker volume mounted at {@code /workspace} so a cell's steps share
     *     one working tree, or null for the default of a clean copy per step.
     */
    public List<String> commandFor(Cell.Step step, Path project, String workspaceVolume) {
        String image = images.get(step.ecosystem());
        if (image == null) {
            throw new IllegalArgumentException("No ecosystem image for '" + step.ecosystem()
                    + "'. Add one under verification/images and register it here.");
        }

        List<String> docker = new ArrayList<>(List.of(
                "timeout",
                "--signal=KILL",
                String.valueOf(timeoutSeconds),
                "docker",
                "run",
                "--rm",
                "--cpus=" + cpus,
                "--memory=" + memory,
                "--memory-swap=" + memory,
                "--pids-limit=2048",
                "--security-opt=no-new-privileges",
                "--add-host=host.docker.internal:host-gateway",
                "-e",
                "TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal",
                "-e",
                "KITBASH_WORKING_DIRECTORY=" + step.workingDirectory(),
                "-e",
                "KITBASH_DOCKER_API_VERSION=" + dockerApiVersion()));

        // The generated project's own suite is Testcontainers-based, so the build needs a daemon.
        // A cell that skips those tests is not verifying very much (§12).
        dockerSocketGroup().ifPresent(group -> {
            docker.add("--group-add");
            docker.add(group);
        });
        docker.add("-v");
        docker.add("/var/run/docker.sock:/var/run/docker.sock");

        if (workspaceVolume != null) {
            docker.add("-v");
            docker.add(workspaceVolume + ":" + WORKSPACES.get(step.ecosystem()));
        }
        docker.add("-v");
        docker.add(project.toAbsolutePath() + ":/input:ro");
        docker.add(image);
        docker.addAll(List.of("sh", "-c", script(step)));
        return List.copyOf(docker);
    }

    /**
     * One shell script for the whole step, rather than one container per command.
     *
     * <p>This is not a tidiness choice. The entrypoint copies the read-only project into a fresh
     * workspace, so a container per command threw away everything the previous command produced —
     * {@code pnpm install} wrote {@code node_modules} and {@code pnpm lint} then ran against a tree
     * that had none. A step is one build, and the commands of one build share a filesystem.
     *
     * <p>The script stops at the first failure and names it, so a step of four commands still
     * reports which of the four broke.
     */
    static String script(Cell.Step step) {
        StringBuilder script = new StringBuilder();
        for (String command : step.commands()) {
            String quoted = quote(command);
            script.append("printf '\\n$ %s\\n' ").append(quoted).append('\n');
            script.append(command)
                    .append(" || { printf '\\n")
                    .append(FAILED)
                    .append("%s\\n' ")
                    .append(quoted)
                    .append("; exit 1; }\n");
        }
        return script.toString();
    }

    /** Single-quotes a command so the script can echo it back verbatim. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * The daemon's own advertised API version.
     *
     * <p>docker-java, which Testcontainers uses, defaults to v1.32 and daemons from Docker 29
     * refuse it outright. Passing what the daemon itself reports is negotiation rather than a pin,
     * so it works on old and new hosts alike.
     */
    private static String dockerApiVersion() {
        return output(List.of("docker", "version", "--format", "{{.Server.APIVersion}}"))
                .orElse("");
    }

    /**
     * The gid of the docker socket.
     *
     * <p>The build runs as a non-root user, so it can only use the mounted socket if it is in that
     * socket's group — and the gid differs between a developer's machine and a CI runner, so it is
     * read rather than baked into the image.
     */
    private static java.util.Optional<String> dockerSocketGroup() {
        return output(List.of("stat", "-c", "%g", "/var/run/docker.sock"));
    }

    private static java.util.Optional<String> output(List<String> command) {
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(false).start();
            String value = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 && !value.isEmpty()
                    ? java.util.Optional.of(value)
                    : java.util.Optional.empty();
        } catch (IOException e) {
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
