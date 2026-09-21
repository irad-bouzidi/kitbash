package com.example.demo;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Points the resource server at a locally generated key instead of a real issuer, for every test
 * in the suite.
 *
 * <p>This is the awkward part of adding auth to a project whose tests already exist, and the
 * reason it is solved here rather than in each of them. {@code issuer-uri} makes Spring fetch
 * OpenID metadata while the context starts, so *every* {@code @SpringBootTest} — the API
 * integration test, anything added later — would fail on a host that does not exist. Overriding it
 * per test class means every test class knows about auth.
 *
 * <p>An {@code EnvironmentPostProcessor} in the test source set applies to all of them and to none
 * of production. It is registered in {@code src/test/resources/META-INF/spring.factories}, which
 * Spring merges with the production one rather than replacing.
 *
 * <p>What it does <b>not</b> do is disable security. The filter chain, the audience check and the
 * signature check are the ones that ship; only the key's origin is local.
 */
public class LocalIssuerEnvironment implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles("test")) {
            return;
        }
        // addFirst, because application.yaml's ${DEMO_AUTH_ISSUER_URI} would otherwise be resolved
        // first and fail — there is no such variable in a test JVM, and that is the point.
        //
        // issuer-uri is emptied rather than removed: Spring picks the issuer branch whenever the
        // property has text, and the public-key branch only when it does not.
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "kitbash-test-issuer",
                        Map.of(
                                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                "",
                                "spring.security.oauth2.resourceserver.jwt.public-key-location",
                                "file:" + TestTokens.publicKeyFile(),
                                "spring.security.oauth2.resourceserver.jwt.audiences",
                                TestTokens.AUDIENCE)));
    }
}
