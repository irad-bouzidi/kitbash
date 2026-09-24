package dev.kitbash.sandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What this host will actually give us, decided once and never assumed (§13, kitbash-47 §6.4).
 *
 * <p>§13 asks for a separate process with no network, hard caps and no access to the host catalog
 * or the database. Of those, the one that cannot be arranged from inside the JVM is the network:
 * the security manager that used to do it is gone, and a JVM cannot revoke its own sockets. It has
 * to come from the operating system, which means a network namespace, which means the host has to
 * permit unprivileged ones.
 *
 * <h2>Fail closed</h2>
 *
 * <p>So this class probes for {@code unshare --map-root-user --net} at startup and remembers the
 * answer. If the probe fails, contributed recipes are <b>refused</b> rather than rendered
 * unconfined.
 *
 * <p>That is the decision worth defending. A sandbox that silently degrades to an ordinary render
 * on a host without user namespaces is worse than having no sandbox at all, because the threat
 * model then says there is one. Everybody downstream — the reviewer approving a recipe, the
 * operator reading the dashboard, the person picking it in the wizard — would be relying on a
 * control that is not running, and nothing would say so.
 *
 * <p>The refusal names the setting, because an operator who sees contributed recipes failing needs
 * to know it is a host capability and not a bug in the recipe.
 *
 * <p>This class logs nothing and depends on nothing. Everything it needs is in the JDK, because
 * its dependencies become the worker's classpath — and the smallest classpath that can render a
 * template is the one with least to escape into. The caller logs the probe's result at boot.
 */
public final class Confinement {

    /**
     * Long enough for a process launch on a loaded host, short enough that a wedged probe does not
     * hold up boot. A probe that times out counts as unavailable, which is the fail-closed answer.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final boolean available;
    private final String reason;

    private Confinement(boolean available, String reason) {
        this.available = available;
        this.reason = reason;
    }

    /**
     * Probes the host once.
     *
     * <p>The probe is not "does {@code unshare} exist" — a binary that exists and then fails to
     * create the namespace would pass that and fail every render afterwards. It runs the real
     * command and checks that the process it produced actually came back.
     */
    public static Confinement probe() {
        try {
            Process process = new ProcessBuilder(command(List.of("true")))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Confinement(false, "the probe did not finish within " + PROBE_TIMEOUT.toSeconds() + "s");
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes()).trim();
                return new Confinement(
                        false,
                        "unshare exited " + process.exitValue() + (output.isEmpty() ? "" : ": " + firstLine(output)));
            }
            return new Confinement(true, "network namespace");
        } catch (IOException unavailable) {
            return new Confinement(false, "unshare is not on the PATH");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Confinement(false, "the probe was interrupted");
        }
    }

    /** For tests that need to exercise the refusal path on a host where confinement does work. */
    public static Confinement unavailable(String reason) {
        return new Confinement(false, reason);
    }

    public boolean isAvailable() {
        return available;
    }

    public String reason() {
        return reason;
    }

    /**
     * Wraps a command so it runs with no network.
     *
     * <p>{@code --map-root-user} is what makes this work without privileges: it creates a user
     * namespace in which the caller is root, and only then is creating a network namespace
     * permitted. The child is not root on the host — the mapping exists inside the namespace and
     * nowhere else.
     *
     * <p>A fresh network namespace has one interface, {@code lo}, and it is down. There is no
     * route off the host, so a template that somehow reached a socket would find nothing to
     * connect to — which is the point, and is what {@code deniesNetwork} in the adversarial suite
     * demonstrates rather than asserts from documentation.
     */
    static List<String> command(List<String> inner) {
        List<String> command = new java.util.ArrayList<>(List.of("unshare", "--map-root-user", "--net", "--"));
        command.addAll(inner);
        return List.copyOf(command);
    }

    private static String firstLine(String output) {
        int newline = output.indexOf('\n');
        return newline < 0 ? output : output.substring(0, newline);
    }
}
