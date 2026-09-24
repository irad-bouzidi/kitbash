package dev.kitbash.api.push;

/**
 * The project exists and the code is not in it (§46).
 *
 * <p>Its own type because it is its own outcome. §46 is explicit: a partially completed push must
 * be reported as exactly that, <i>with the created project named</i>, rather than rolled back
 * silently or reported as a generic failure.
 *
 * <p>Both alternatives are worse. A rollback deletes something the user can already see in GitLab
 * and may have started using; a generic failure leaves them with an empty project and no idea
 * where it came from. Naming it lets them push the zip by hand into the repository that is already
 * waiting.
 */
public class PartialPushException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String projectUrl;
    private final transient String path;

    public PartialPushException(String projectUrl, String path, String message) {
        super(message);
        this.projectUrl = projectUrl;
        this.path = path;
    }

    public String projectUrl() {
        return projectUrl;
    }

    public String path() {
        return path;
    }
}
