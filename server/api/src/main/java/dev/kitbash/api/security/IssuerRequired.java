package dev.kitbash.api.security;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Refuses to start without an issuer, and says so in a sentence.
 *
 * <p>Without this, a missing issuer surfaces as Spring's "Jwt decoder not configured" somewhere in
 * a filter-chain stack trace, which sends whoever is deploying to read Spring Security's source
 * instead of their own environment.
 *
 * <p>Failing rather than falling back to an open service is the whole point. §13 closes every
 * endpoint but health; a service that started without an issuer would be one that could not verify
 * a token and therefore could not close anything.
 */
public class IssuerRequired implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String JWK_SET = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment environment = event.getEnvironment();
        if (isSet(environment, ISSUER) || isSet(environment, JWK_SET)) {
            return;
        }
        throw new IllegalStateException(
                """
                No OIDC issuer is configured, so no token could be verified and every request would \
                have to be refused.

                Set KITBASH_OIDC_ISSUER to the identity provider's issuer URL, or run with \
                SPRING_PROFILES_ACTIVE=dev-auth for the stub issuer that `docker compose up` \
                starts on http://localhost:9500/kitbash.""");
    }

    private static boolean isSet(Environment environment, String property) {
        String value = environment.getProperty(property);
        return value != null && !value.isBlank();
    }
}
