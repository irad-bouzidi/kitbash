package dev.kitbash.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may do what (§13, §18).
 *
 * <p>§18 settles the audience — one internal team behind the SSO they already have — and that
 * decides the shape of everything here. There is no registration, no password handling and no
 * tenancy; a caller arrives with a token from the identity provider the company already runs, and
 * {@code owner_id} is that token's subject.
 *
 * <p>The split §13 asks for, in one place so it can be read as a sentence:
 *
 * <ul>
 *   <li>the catalog and {@code /generate} — any authenticated user;
 *   <li>writing a preset, and publishing one — a role;
 *   <li>{@code /verify} — any authenticated user, throttled by concurrency rather than by count
 *       ({@code kitbash-37}), because the expensive thing about verification is how many run at
 *       once, not how many are asked for.
 * </ul>
 *
 * <p>Everything except the health endpoint is closed. An unauthenticated caller gets the §14
 * envelope rather than Spring's default empty 401 — a client that has to guess why it was refused
 * is a client that shows a blank screen.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    private final SecurityProperties roles;

    public SecurityConfiguration(SecurityProperties roles) {
        this.roles = roles;
    }

    @Bean
    public SecurityFilterChain api(HttpSecurity http, ProblemDetailAuthenticationHandlers handlers) throws Exception {
        return http
                // No cookies, no sessions, no CSRF token: every request carries its own bearer
                // token, so there is no ambient authority for a forged request to ride on.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Liveness has to answer before anything else works, including the
                        // identity provider — a health check that needs SSO cannot report that
                        // SSO is down.
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()

                        // Reading the catalog and generating a project: any authenticated user.
                        // Generation is the product, and gating it behind a role would mean
                        // onboarding somebody twice.
                        .requestMatchers(HttpMethod.GET, "/api/v1/metadata")
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/validate", "/api/v1/preview", "/api/v1/generate")
                        .authenticated()

                        // Presets are shared objects: writing one is a role. Reading stays open to
                        // any authenticated user, because a preset nobody can read is not worth
                        // saving — which of them a caller may see is the service's decision, since
                        // it depends on the row rather than on the path.
                        .requestMatchers(HttpMethod.GET, "/api/v1/presets", "/api/v1/presets/**")
                        .authenticated()
                        // Generating from a preset is generating: no role, same as /generate.
                        .requestMatchers(HttpMethod.POST, "/api/v1/presets/*/generate")
                        .authenticated()
                        // Publishing — making a preset visible to everybody — needs the second role,
                        // and it is enforced in PresetService rather than here: visibility is a
                        // field in a body, and no path pattern can see one.
                        .requestMatchers(HttpMethod.POST, "/api/v1/presets", "/api/v1/presets/**")
                        .hasAuthority(roles.presetAuthorAuthority())
                        .requestMatchers(HttpMethod.PUT, "/api/v1/presets/**")
                        .hasAuthority(roles.presetAuthorAuthority())
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/presets/**")
                        .hasAuthority(roles.presetAuthorAuthority())

                        // History is personal but needs no role: a receipt for something you
                        // generated is yours, and the service decides which rows are whose.
                        .requestMatchers("/api/v1/generations", "/api/v1/generations/**")
                        .authenticated()

                        // Everything else — including the OpenAPI document and anything added
                        // tomorrow — is closed until somebody opens it deliberately.
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                                .authenticationEntryPoint(handlers.unauthenticated())
                                .accessDeniedHandler(handlers.forbidden()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers.unauthenticated())
                        .accessDeniedHandler(handlers.forbidden()))
                .build();
    }

    /**
     * Scopes and roles, both.
     *
     * <p>Spring's default converter reads {@code scope} and prefixes {@code SCOPE_}. The roles this
     * service authorises on arrive in a claim the identity provider chooses, so that claim is read
     * as well — configured rather than hardcoded, because renaming a group should not be a deploy.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            rolesOf(jwt).forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            return authorities;
        });
        return converter;
    }

    /**
     * The roles claim, which providers spell in several shapes.
     *
     * <p>A list is the common case; a single string happens; anything else is ignored rather than
     * guessed at, because inventing a role from a claim nobody meant as one is the wrong kind of
     * generous.
     */
    private List<String> rolesOf(Jwt jwt) {
        Object claim = jwt.getClaim(roles.rolesClaim());
        return switch (claim) {
            case Collection<?> values -> values.stream().map(String::valueOf).toList();
            case String value when !value.isBlank() -> List.of(value.split("[ ,]+"));
            case null, default -> List.of();
        };
    }
}
