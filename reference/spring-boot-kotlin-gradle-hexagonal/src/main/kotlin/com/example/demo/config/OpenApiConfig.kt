package com.example.demo.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    // Deliberately not named after the project: the project name becomes a generator
    // variable, and a kebab-case one does not fit inside a Kotlin identifier. Names that
    // vary between generated projects belong in strings, never in identifiers.
    @Bean
    fun openApi(properties: DemoProperties): OpenAPI =
        OpenAPI().info(
            Info()
                .title("demo API")
                .version("v1")
                .description("Running in the '${properties.environmentName}' environment."),
        )
}
