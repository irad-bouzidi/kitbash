package dev.kitbash.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A token's claims become authorities (§13).
 *
 * <p>Separate from {@code SecurityTest} because the two halves fail differently and it is worth
 * knowing which broke. That test asserts the <i>rules</i> — which path needs which authority — over
 * a MockMvc request whose authorities the test framework supplies directly. This one asserts the
 * <i>conversion</i>, which is the part a new identity provider changes: a different claim name, a
 * space-separated string instead of a list, a role that is not there at all.
 */
class RoleMappingTest {

    private final SecurityProperties roles =
            new SecurityProperties("roles", "kitbash-author", "kitbash-publisher", "kitbash-recipe-reviewer");

    @Test
    @DisplayName("roles in the configured claim become ROLE_ authorities")
    void mapsTheConfiguredClaim() {
        assertThat(authorities(Map.of("roles", List.of("kitbash-author", "kitbash-publisher"))))
                .contains("ROLE_kitbash-author", "ROLE_kitbash-publisher");
    }

    /**
     * The whole reason the claim name is configuration. Keycloak, Entra and Okta each put groups
     * somewhere different, and renaming a claim should be an environment variable rather than a
     * release.
     */
    @Test
    @DisplayName("a provider that names the claim differently is a configuration change")
    void readsWhicheverClaimIsConfigured() {
        SecurityProperties groups =
                new SecurityProperties("groups", "kitbash-author", "kitbash-publisher", "kitbash-recipe-reviewer");

        assertThat(authorities(groups, Map.of("groups", List.of("kitbash-author"))))
                .contains("ROLE_kitbash-author");
        assertThat(authorities(groups, Map.of("roles", List.of("kitbash-author"))))
                .as("a claim nobody configured is not a source of authority")
                .doesNotContain("ROLE_kitbash-author");
    }

    @Test
    @DisplayName("a single space- or comma-separated string works, because some providers send one")
    void acceptsAStringOfRoles() {
        assertThat(authorities(Map.of("roles", "kitbash-author kitbash-publisher")))
                .contains("ROLE_kitbash-author", "ROLE_kitbash-publisher");
        assertThat(authorities(Map.of("roles", "kitbash-author,kitbash-publisher")))
                .contains("ROLE_kitbash-author", "ROLE_kitbash-publisher");
    }

    @Test
    @DisplayName("scopes still map, so an existing scope-based rule keeps working")
    void keepsScopes() {
        assertThat(authorities(Map.of("scope", "openid profile"))).contains("SCOPE_openid", "SCOPE_profile");
    }

    /**
     * A claim shaped like nothing expected is ignored rather than guessed at. Inventing a role
     * from a number or an object would be the wrong kind of generous: the failure mode is somebody
     * holding a permission nobody granted.
     */
    @Test
    @DisplayName("a token with no roles claim simply has no roles")
    void missingClaimGrantsNothing() {
        assertThat(authorities(Map.of("sub", "alice"))).noneMatch(authority -> authority.startsWith("ROLE_"));
        assertThat(authorities(Map.of("roles", 7))).noneMatch(authority -> authority.startsWith("ROLE_"));
        assertThat(authorities(Map.of("roles", ""))).noneMatch(authority -> authority.startsWith("ROLE_"));
    }

    private List<String> authorities(Map<String, Object> claims) {
        return authorities(roles, claims);
    }

    private List<String> authorities(SecurityProperties properties, Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(3600))
                .claims(all -> all.putAll(claims))
                .build();

        return new SecurityConfiguration(properties)
                .jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList();
    }
}
