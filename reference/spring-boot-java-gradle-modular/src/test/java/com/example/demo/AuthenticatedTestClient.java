package com.example.demo;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes every test's HTTP calls authenticated, without any test asking.
 *
 * <p>This is the whole reason auth is a good test of the recipe engine. Turning it on protects
 * endpoints that other recipes' tests already call, and the alternative — a marker in each of those
 * tests for auth to insert a header into — would put an auth seam in the database recipe. Spring
 * Boot builds its {@code TestRestTemplate} from a {@code RestTemplateBuilder} bean when one exists,
 * so one bean in the test source set covers every test in the suite and none of them mention auth.
 *
 * <p>It is a {@code @Configuration} in {@code src/test}, so it is component-scanned by
 * {@code @SpringBootTest} and cannot reach production. {@link AuthenticationTest} builds its own
 * bare client for the cases that must arrive without a token.
 */
@Configuration
public class AuthenticatedTestClient {

    @Bean
    RestTemplateBuilder testRestTemplateBuilder() {
        return new RestTemplateBuilder().additionalInterceptors((request, body, execution) -> {
            request.getHeaders().setBearerAuth(TestTokens.valid());
            return execution.execute(request, body);
        });
    }
}
