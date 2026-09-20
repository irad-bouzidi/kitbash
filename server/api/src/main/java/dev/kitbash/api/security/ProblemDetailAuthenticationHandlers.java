package dev.kitbash.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * A refusal is a problem document, not an empty 401.
 *
 * <p>Spring Security's defaults answer with a bare status and a {@code WWW-Authenticate} header.
 * That is correct HTTP and useless to the wizard, which has one place that renders a failure and
 * renders it from the §14 fields. A client that has to special-case "no body, status 401" is a
 * client that shows a blank screen the first time something is misconfigured.
 *
 * <p>So the two security outcomes speak the same vocabulary as every other error: a code, a
 * message and a hint naming the next action.
 */
@Component
public class ProblemDetailAuthenticationHandlers {

    private final ObjectMapper json;

    public ProblemDetailAuthenticationHandlers(ObjectMapper json) {
        this.json = json;
    }

    /** No token, or one the issuer will not vouch for. */
    public AuthenticationEntryPoint unauthenticated() {
        return (request, response, exception) -> write(
                response,
                HttpStatus.UNAUTHORIZED,
                "Not signed in",
                "This request carried no valid token.",
                "UNAUTHENTICATED",
                "Sign in and retry. Every endpoint except /actuator/health needs a token from the "
                        + "identity provider.");
    }

    /** A valid token, from somebody the roles do not cover. */
    public AccessDeniedHandler forbidden() {
        return (request, response, exception) -> write(
                response,
                HttpStatus.FORBIDDEN,
                "Not allowed",
                "Your account is signed in but does not have the role this action needs.",
                "FORBIDDEN",
                "Writing or publishing a preset needs a role; generating a project does not. Ask an "
                        + "administrator to add you to the right group.");
    }

    private void write(
            HttpServletResponse response, HttpStatus status, String title, String detail, String code, String hint)
            throws java.io.IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("error", code);
        problem.setProperty("hint", hint);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
