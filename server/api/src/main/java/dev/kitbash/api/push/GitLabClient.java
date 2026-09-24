package dev.kitbash.api.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Creating a project in a GitLab group (§46).
 *
 * <p>Only what pushing needs: find the group, create the project. Not a GitLab SDK — this calls
 * two endpoints, and a client library would be a dependency, a version to track and a surface
 * larger than the code it replaced.
 *
 * <h2>The failure modes, by name</h2>
 *
 * <p>§46 asks for explicit handling of the four that will actually occur, and they are not
 * distinguishable from a status code alone — GitLab answers 400 both for a name already taken and
 * for a path that is not allowed, and 404 both for a group that does not exist and for one the
 * token cannot see. So the body is read, and the difference is turned into a hint that names the
 * next action rather than the HTTP code.
 */
@Component
public class GitLabClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One project, as it now exists. */
    public record Project(long id, String path, String webUrl, String httpUrl) {}

    /**
     * Finds a group by its full path, as a user would type it: {@code team/subgroup}.
     *
     * <p>By path rather than by search: a search matches names, and two groups in different
     * namespaces can share one. A path is what a user copies out of a URL.
     */
    public long groupId(String baseUrl, String token, String fullPath) {
        HttpResponse<String> response = send(baseUrl, token, "GET", "/groups/" + encode(fullPath), null);

        if (response.statusCode() == 404) {
            // 404 covers both "no such group" and "your token cannot see it", and GitLab will not
            // say which — deliberately, since saying so would confirm a private group exists. The
            // hint has to cover both, because the server genuinely does not know.
            throw new PushRefusedException(
                    "GROUP_NOT_FOUND",
                    "No group '" + fullPath + "' that this account can see.",
                    "Check the path as it appears in the group's URL. A group you are not a member "
                            + "of looks the same as one that does not exist, so ask for access if you "
                            + "expected to have it.");
        }
        require(response, "the group could not be read");
        return read(response).path("id").asLong();
    }

    /**
     * Creates a project in the group, and says what to do when it will not.
     *
     * <p>{@code initialize_with_readme} is deliberately absent. A project created with a commit
     * already in it cannot receive the zip's commit as its first — the push would be rejected as
     * non-fast-forward, and the guarantee §46 asks for would be gone.
     */
    public Project createProject(String baseUrl, String token, long groupId, String name) {
        String body =
                "name=%s&path=%s&namespace_id=%d&visibility=private".formatted(encode(name), encode(name), groupId);
        HttpResponse<String> response = send(baseUrl, token, "POST", "/projects", body);

        if (response.statusCode() == 400) {
            String message = response.body();
            if (message.contains("has already been taken")) {
                throw new PushRefusedException(
                        "PROJECT_EXISTS",
                        "A project called '" + name + "' already exists in that group.",
                        "Pick another name, or push to the existing project yourself — this creates "
                                + "repositories and never writes to one that is already there.");
            }
            throw new PushRefusedException(
                    "PROJECT_REFUSED",
                    "GitLab refused to create '" + name + "' in that group.",
                    "The name may break a group rule. " + firstError(message));
        }
        if (response.statusCode() == 403) {
            throw new PushRefusedException(
                    "GROUP_FORBIDDEN",
                    "This account cannot create projects in that group.",
                    "Creating a project needs Developer or above in the group. Ask an owner, or "
                            + "choose a group where you have it.");
        }
        require(response, "the project could not be created");

        JsonNode created = read(response);
        return new Project(
                created.path("id").asLong(),
                created.path("path_with_namespace").asText(),
                created.path("web_url").asText(),
                created.path("http_url_to_repo").asText());
    }

    private static String firstError(String body) {
        try {
            JsonNode message = JSON.readTree(body).path("message");
            if (message.isObject() && message.properties().iterator().hasNext()) {
                var first = message.properties().iterator().next();
                return "GitLab said: " + first.getKey() + " "
                        + first.getValue().get(0).asText() + ".";
            }
            if (!message.isMissingNode()) {
                return "GitLab said: " + message.asText() + ".";
            }
        } catch (IOException unreadable) {
            // Falls through to the generic sentence.
        }
        return "GitLab did not say which rule.";
    }

    private HttpResponse<String> send(String baseUrl, String token, String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v4" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                // A bearer token, not a private token header: this is an OAuth token obtained for
                // the user, and its scope is `api` — which docs/gitlab-push.md names, because a
                // feature that creates repositories is a privilege worth stating.
                .header("Authorization", "Bearer " + token);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }

        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException unreachable) {
            throw new PushRefusedException(
                    "GITLAB_UNREACHABLE",
                    "GitLab at " + baseUrl + " is not answering.",
                    "Check the instance URL, and that this server can reach it. Nothing was created.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PushRefusedException(
                    "GITLAB_UNREACHABLE", "The request to GitLab was interrupted.", "Try again.");
        }
    }

    private static void require(HttpResponse<String> response, String what) {
        if (response.statusCode() == 401) {
            // The likeliest failure of the four, and the one a generic "GitLab refused" serves
            // worst: an expired token and a mistyped instance URL produce the same 401, and the
            // person reading it has to be told which two things to check.
            throw new PushRefusedException(
                    "TOKEN_REJECTED",
                    "GitLab did not accept that token.",
                    "Tokens expire. Mint a new one with the 'api' scope, and check the instance "
                            + "URL is the one it belongs to. Nothing was created.");
        }
        if (response.statusCode() / 100 != 2) {
            throw new PushRefusedException(
                    "GITLAB_REFUSED",
                    what + " (HTTP " + response.statusCode() + ").",
                    "Nothing was created. " + firstError(response.body()));
        }
    }

    private static JsonNode read(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new PushRefusedException(
                    "GITLAB_REFUSED",
                    "GitLab's answer was not readable JSON.",
                    "This usually means the URL points at something that is not a GitLab instance.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
