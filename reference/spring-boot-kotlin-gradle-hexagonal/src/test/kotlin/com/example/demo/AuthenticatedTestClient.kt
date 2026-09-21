package com.example.demo

import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Makes every test's HTTP calls authenticated, without any test asking.
 *
 * This is the whole reason auth is a good test of the recipe engine. Turning it on protects
 * endpoints that other recipes' tests already call, and the alternative — a marker in each of those
 * tests for auth to insert a header into — would put an auth seam in the database recipe. Spring
 * Boot builds its `TestRestTemplate` from a `RestTemplateBuilder` bean when one exists, so one bean
 * in the test source set covers every test in the suite and none of them mention auth.
 *
 * It is a `@Configuration` in `src/test`, so it is component-scanned by `@SpringBootTest` and
 * cannot reach production. [AuthenticationTest] builds its own bare client for the cases that must
 * arrive without a token.
 */
@Configuration
class AuthenticatedTestClient {
    @Bean
    fun testRestTemplateBuilder(): RestTemplateBuilder =
        RestTemplateBuilder().additionalInterceptors({ request, body, execution ->
            request.headers.setBearerAuth(TestTokens.valid())
            execution.execute(request, body)
        })
}
