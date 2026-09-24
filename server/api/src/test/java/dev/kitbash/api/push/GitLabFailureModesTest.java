package dev.kitbash.api.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four failure modes §46 names, and the two it implies (§14, §46).
 *
 * <p>§46 asks for explicit handling of <i>name already taken, insufficient permission in the
 * group, group not visible to the token, push rejected by a group policy</i> — and the first three
 * are indistinguishable from a status code. GitLab answers 400 for a taken name <i>and</i> for a
 * name that breaks a rule; 404 for a group that is not there <i>and</i> for one the token cannot
 * see. What separates them is the body, so the body is what this reads.
 *
 * <p>Against a real HTTP server rather than a mocked client, because the thing under test is the
 * decoding of somebody else's responses, and a mock that returns what the code expects proves
 * nothing about a response GitLab actually sends. The bodies below are copied from GitLab's shapes.
 */
class GitLabFailureModesTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> authorizations = new ArrayList<>();

    private final GitLabClient client = new GitLabClient();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Answers every request under a path with one status and one body. */
    private void answer(String path, int status, String body) {
        server.createContext(path, exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void expectRefusal(Runnable call, String code, Consumer<PushRefusedException> checks) {
        assertThatThrownBy(call::run).isInstanceOf(PushRefusedException.class).satisfies(thrown -> {
            PushRefusedException refusal = (PushRefusedException) thrown;
            assertThat(refusal.code()).isEqualTo(code);
            // §14's rule, applied here too: a hint that does not name an action is not a
            // hint. These are not GenerationErrors, so ErrorDetail's constructor is not
            // enforcing it — this is.
            assertThat(refusal.hint()).isNotBlank().hasSizeGreaterThan(20);
            checks.accept(refusal);
        });
    }

    @Nested
    @DisplayName("finding the group")
    class FindingTheGroup {

        @Test
        @DisplayName("a 404 covers both 'no such group' and 'not yours', so the hint covers both")
        void groupNotFound() {
            answer("/api/v4/groups/", 404, "{\"message\":\"404 Group Not Found\"}");

            expectRefusal(() -> client.groupId(baseUrl, "t", "acme/platform"), "GROUP_NOT_FOUND", refusal -> {
                // The path the user typed, echoed back: "not found" without it is a sentence they
                // cannot act on when they have three tabs open.
                assertThat(refusal.getMessage()).contains("acme/platform");
                assertThat(refusal.hint()).contains("ask for access");
            });
        }

        @Test
        @DisplayName("an expired token is told apart from an unreachable instance")
        void tokenRejected() {
            answer("/api/v4/groups/", 401, "{\"message\":\"401 Unauthorized\"}");

            expectRefusal(
                    () -> client.groupId(baseUrl, "stale", "acme/platform"),
                    "TOKEN_REJECTED",
                    refusal -> assertThat(refusal.hint()).contains("expire").contains("api"));
        }

        @Test
        @DisplayName("the group is looked up by path, and the token travels as a bearer")
        void asksTheRightQuestion() {
            answer("/api/v4/groups/", 200, "{\"id\":42}");

            assertThat(client.groupId(baseUrl, "a-token", "acme/platform")).isEqualTo(42L);
            assertThat(authorizations).containsExactly("Bearer a-token");
        }
    }

    @Nested
    @DisplayName("creating the project")
    class CreatingTheProject {

        @Test
        @DisplayName("a taken name says so, and says this never writes into an existing project")
        void nameTaken() {
            answer("/api/v4/projects", 400, "{\"message\":{\"name\":[\"has already been taken\"]}}");

            expectRefusal(() -> client.createProject(baseUrl, "t", 42, "billing"), "PROJECT_EXISTS", refusal -> {
                assertThat(refusal.getMessage()).contains("billing");
                // The distinction that keeps this honest: the project that exists is untouched.
                assertThat(refusal.hint()).contains("never writes to one that is already there");
            });
        }

        @Test
        @DisplayName("a group rule that rejects the name is quoted rather than summarised")
        void refusedByAGroupRule() {
            answer("/api/v4/projects", 400, "{\"message\":{\"path\":[\"must not start with a digit\"]}}");

            expectRefusal(
                    () -> client.createProject(baseUrl, "t", 42, "9lives"),
                    "PROJECT_REFUSED",
                    // GitLab knows the rule and this code does not, so the hint carries GitLab's
                    // own words. Paraphrasing them would lose the only actionable part.
                    refusal -> assertThat(refusal.hint()).contains("must not start with a digit"));
        }

        @Test
        @DisplayName("insufficient permission names the role that would be enough")
        void forbidden() {
            answer("/api/v4/projects", 403, "{\"message\":\"403 Forbidden\"}");

            expectRefusal(
                    () -> client.createProject(baseUrl, "t", 42, "billing"),
                    "GROUP_FORBIDDEN",
                    refusal -> assertThat(refusal.hint()).contains("Developer"));
        }

        @Test
        @DisplayName("the project is created without a README, or it could not take the zip's commit")
        void createsAnEmptyProject() throws Exception {
            List<String> bodies = new ArrayList<>();
            server.createContext("/api/v4/projects", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] created = ("{\"id\":7,\"path_with_namespace\":\"acme/platform/billing\","
                                + "\"web_url\":\"https://gl/acme/platform/billing\","
                                + "\"http_url_to_repo\":\"https://gl/acme/platform/billing.git\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, created.length);
                exchange.getResponseBody().write(created);
                exchange.close();
            });

            GitLabClient.Project project = client.createProject(baseUrl, "t", 42, "billing");

            assertThat(project.path()).isEqualTo("acme/platform/billing");
            assertThat(project.httpUrl()).isEqualTo("https://gl/acme/platform/billing.git");
            // The load-bearing absence. A project initialised with a README already has a commit,
            // so the zip's commit could not be its first and the §46 guarantee would be gone.
            assertThat(bodies)
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .doesNotContain("initialize_with_readme")
                    .contains("namespace_id=42")
                    .contains("visibility=private");
        }
    }

    @Test
    @DisplayName("an instance that is not there is a refusal that says nothing was created")
    void unreachable() {
        // A port nothing is listening on: the closed-connection path, which is what a wrong
        // instance URL actually looks like.
        String nowhere = "http://127.0.0.1:1";

        expectRefusal(
                () -> client.groupId(nowhere, "t", "acme/platform"),
                "GITLAB_UNREACHABLE",
                // The half a user most needs after a network failure: whether they now own a
                // half-made project somewhere.
                refusal -> assertThat(refusal.hint()).contains("Nothing was created"));
    }
}
