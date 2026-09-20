package dev.kitbash.api.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The names of things, kept out of the code (§13).
 *
 * <p>Roles and the claim they arrive in are configuration because the mapping to an identity
 * provider's groups is the part most likely to change, and it should change without a deploy. A
 * hardcoded {@code hasRole("KITBASH_ADMIN")} is a redeploy every time somebody renames a group.
 *
 * @param rolesClaim the JWT claim holding the caller's groups or roles
 * @param presetAuthor the role a caller needs to write presets
 * @param publisher the role a caller needs to make a preset public
 */
@ConfigurationProperties(prefix = "kitbash.security")
public record SecurityProperties(String rolesClaim, String presetAuthor, String publisher) {

    public SecurityProperties {
        rolesClaim = rolesClaim == null || rolesClaim.isBlank() ? "roles" : rolesClaim;
        presetAuthor = presetAuthor == null || presetAuthor.isBlank() ? "kitbash-author" : presetAuthor;
        publisher = publisher == null || publisher.isBlank() ? "kitbash-publisher" : publisher;
    }

    /**
     * Spring Security's authority spelling of a configured role.
     *
     * <p>The {@code ROLE_} prefix is Spring's convention rather than the identity provider's, so
     * it is added here instead of being written into the configuration — otherwise every
     * deployment's config would carry a Spring implementation detail.
     */
    public String presetAuthorAuthority() {
        return "ROLE_" + presetAuthor;
    }

    public String publisherAuthority() {
        return "ROLE_" + publisher;
    }

    public List<String> knownRoles() {
        return List.of(presetAuthor, publisher);
    }
}
