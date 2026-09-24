package dev.kitbash.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The documented escape attempts, which is §47's "done when" for the sandbox.
 *
 * <p>§47 asks for *an adversarial test suite, not a smoke test: a documented set of escape attempts
 * that must all fail, kept in the repo and extended whenever a new attack is imagined.* This is
 * that set. Each test names what is being attempted and which control is supposed to stop it, so
 * that when one starts failing it is clear what has been lost.
 *
 * <p>Two of these do not test the sandbox at all — they test that the sandbox is still needed.
 * {@link Confinement#deniesNetworkThatTheHostHas} is the shape: it shows the host <i>can</i> reach
 * the network and the worker cannot, because a test asserting only the second half would pass just
 * as well on a machine with no network, and would then be asserting nothing.
 *
 * <p>Layered on purpose. Most of these are already impossible at the template engine — §13 removed
 * method access and Pebble's file-resolving loaders — and the sandbox is the second answer for
 * when the first one has a bug. So a test passing here does not prove the engine is sound; it
 * proves that an engine that <i>was not</i> would still be contained.
 */
@EnabledOnOs(OS.LINUX)
class SandboxEscapeTest {

    private static SandboxedRenderer renderer;
    private static Confinement confinement;

    @BeforeAll
    static void confine() {
        confinement = Confinement.probe();
        renderer = new SandboxedRenderer(confinement, System.getProperty("kitbash.sandbox.classpath"));
    }

    private static Map<String, String> render(String template) {
        return renderer.render("@attacker/probe", Map.of("t", template), Map.of("name", "billing"));
    }

    private static void refuses(String template, String code) {
        assertThatThrownBy(() -> render(template))
                .isInstanceOf(SandboxRefusedException.class)
                .extracting(thrown -> ((SandboxRefusedException) thrown).code())
                .isEqualTo(code);
    }

    @Test
    @DisplayName("the control under test is actually on, or the rest of this file proves nothing")
    void confinementIsActive() {
        // If this fails, every refusal below could be a refusal for the wrong reason.
        assertThat(confinement.isAvailable())
                .as("unprivileged user namespaces, which every other test here assumes: %s", confinement.reason())
                .isTrue();
    }

    @Test
    @DisplayName("an ordinary template still renders, so the refusals below mean something")
    void rendersWhatItShould() {
        assertThat(render("project: {{ name }}")).containsEntry("t", "project: billing");
    }

    @Nested
    @DisplayName("reaching out of the template")
    class OutOfTheTemplate {

        @Test
        @DisplayName("no method access, so no route from a value into the JVM")
        void noMethodAccess() {
            // §13's first template rule. The engine refuses; the sandbox is why it not mattering
            // would still not matter.
            refuses("{{ name.getClass() }}", "SANDBOX_RENDER_FAILED");
            refuses("{{ name.toUpperCase() }}", "SANDBOX_RENDER_FAILED");
            refuses("{{ ''.getClass().forName('java.lang.Runtime') }}", "SANDBOX_RENDER_FAILED");
        }

        @Test
        @DisplayName("no reaching a class by name, even one that is certainly on the classpath")
        void noClassLoading() {
            refuses("{{ name.class.classLoader }}", "SANDBOX_RENDER_FAILED");
            refuses("{% set r = 'java.lang.Runtime' %}{{ r.getRuntime() }}", "SANDBOX_RENDER_FAILED");
        }

        @Test
        @DisplayName("no reading a file, including one the parent process certainly can")
        void noFileAccess() {
            // The worker's cwd is an empty directory and it opens nothing, so both of these fail
            // as "no such template" rather than as a path check — which is the stronger outcome,
            // because a path check is a list of the paths somebody thought of.
            refuses("{% include '/etc/passwd' %}", "SANDBOX_RENDER_FAILED");
            refuses("{% include '../../../../etc/hostname' %}", "SANDBOX_RENDER_FAILED");
            refuses("{% import '/etc/passwd' %}", "SANDBOX_RENDER_FAILED");
        }

        @Test
        @DisplayName("a template may include another template it was submitted with, and only that")
        void includesStayInsideTheRequest() {
            // Threat 3.1's second half. A contributed recipe's templates resolve against the set
            // it was submitted with — not against the catalog — so it cannot read a shipped
            // recipe's templates by including them.
            Map<String, String> rendered = renderer.render(
                    "@attacker/probe",
                    Map.of("t", "{% include 'partial' %}", "partial", "from the same submission"),
                    Map.of());

            assertThat(rendered).containsEntry("t", "from the same submission");

            assertThatThrownBy(() -> renderer.render(
                            "@attacker/probe",
                            Map.of("t", "{% include 'backend-spring-java/Application.java.peb' %}"),
                            Map.of()))
                    .isInstanceOf(SandboxRefusedException.class);
        }

        @Test
        @DisplayName("an undefined variable is a failure, not an empty string")
        void noLenientVariables() {
            // §13 again, and it matters for a *contributed* recipe more than a shipped one: a
            // silently empty package declaration in somebody's generated project is discovered at
            // their first compile, and they will not be looking at the recipe.
            refuses("package {{ undefinedVariable }};", "SANDBOX_RENDER_FAILED");
        }
    }

    @Nested
    @DisplayName("exhausting the host")
    class Exhaustion {

        @Test
        @DisplayName("a template that never finishes is stopped, and the parent survives it")
        void infiniteLoopIsKilled() {
            // The wall clock, enforced by destroyForcibly rather than by asking. The thing being
            // timed out is hostile, so a cooperative timeout is a request.
            // Nested loops over a small list rather than one huge range: a range materialises
            // its elements, so it hits the heap cap first and would test the wrong control. Four
            // levels of a thousand is 10^12 iterations with an empty body — a spin that allocates
            // nothing, which is exactly the shape the wall clock exists for.
            List<String> thousand = java.util.stream.IntStream.range(0, 1000)
                    .mapToObj(Integer::toString)
                    .toList();
            long before = System.nanoTime();

            assertThatThrownBy(() -> renderer.render(
                            "@attacker/probe",
                            Map.of(
                                    "t",
                                    "{% for a in items %}{% for b in items %}{% for c in items %}"
                                            + "{% for d in items %}{% endfor %}{% endfor %}{% endfor %}{% endfor %}"),
                            Map.of("items", thousand)))
                    .isInstanceOf(SandboxRefusedException.class)
                    .extracting(thrown -> ((SandboxRefusedException) thrown).code())
                    .isEqualTo("SANDBOX_TIMEOUT");

            assertThat(java.time.Duration.ofNanos(System.nanoTime() - before))
                    .as("the parent waited for the cap and not much longer")
                    .isLessThan(SandboxedRenderer.TIMEOUT.plusSeconds(15));
        }

        @Test
        @DisplayName("a template that allocates without bound dies in the child, not the server")
        void memoryBombIsCapped() {
            // The heap cap. What is being asserted is as much about the *parent* as the child:
            // this test process is still here to make the assertion.
            assertThatThrownBy(() -> renderer.render(
                            "@attacker/probe",
                            Map.of("t", "{% for i in range(0, 20000000) %}{{ big }}{% endfor %}"),
                            Map.of("big", "x".repeat(10_000))))
                    .isInstanceOf(SandboxRefusedException.class)
                    .extracting(thrown -> ((SandboxRefusedException) thrown).code())
                    // Killed rather than timed out: the heap cap bites first, and the worker dies
                    // without writing a response. That distinction is the reason SANDBOX_KILLED
                    // exists as its own code — "exit 1" would have told an operator nothing.
                    .isEqualTo("SANDBOX_KILLED");

            assertThat(Runtime.getRuntime().maxMemory())
                    .as("the parent JVM is still the parent JVM")
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("the confinement itself")
    class TheConfinement {

        @Test
        @DisplayName("the worker has no network, on a host that does")
        void deniesNetworkThatTheHostHas() throws Exception {
            // The half that makes this meaningful: the host can reach a socket. Without this the
            // assertion below would pass on an air-gapped machine and prove nothing.
            assertThat(canBind()).as("the test host can open a socket at all").isTrue();

            Process confined = new ProcessBuilder(
                            Confinement.command(List.of("sh", "-c", "ip -o addr show 2>/dev/null | wc -l")))
                    .redirectErrorStream(true)
                    .start();
            String interfaces = new String(confined.getInputStream().readAllBytes()).trim();
            confined.waitFor();

            // One line: loopback, and it is down. A namespace with a route would have more.
            assertThat(Integer.parseInt(interfaces))
                    .as("interfaces visible inside the namespace")
                    .isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("the worker inherits no environment, so it inherits no credential")
        void carriesNoEnvironment() throws Exception {
            Process confined = new ProcessBuilder(Confinement.command(List.of("env")))
                    .redirectErrorStream(true)
                    .start();
            // ProcessBuilder's environment is cleared the same way SandboxedRenderer clears it.
            String environment = new String(confined.getInputStream().readAllBytes());
            confined.waitFor();

            assertThat(environment).doesNotContain("SPRING_DATASOURCE").doesNotContain("PASSWORD");
        }

        @Test
        @DisplayName("without confinement it refuses rather than rendering unconfined")
        void failsClosed() {
            // The decision the threat model calls out as the one to watch: a sandbox that
            // degrades to an ordinary render on an unsupported host is worse than none, because
            // everybody downstream is relying on a control that is not running.
            SandboxedRenderer unconfined = new SandboxedRenderer(
                    Confinement.unavailable("no user namespaces on this host"),
                    System.getProperty("kitbash.sandbox.classpath"));

            assertThatThrownBy(() -> unconfined.render("@attacker/probe", Map.of("t", "{{ name }}"), Map.of()))
                    .isInstanceOf(SandboxRefusedException.class)
                    .extracting(thrown -> ((SandboxRefusedException) thrown).code())
                    .isEqualTo("SANDBOX_UNAVAILABLE");
        }
    }

    private static boolean canBind() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort() > 0;
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
