package com.example.demo.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Application settings, bound and validated at startup. Binding failures are startup failures:
 * a misconfigured service should not accept its first request and then fall over.
 *
 * Not named after the project, for the reason `OpenApiConfig` gives about its title: a name that
 * varies between generated projects belongs in a string, never in an identifier. It also used to:
 * a long enough project name pushed this declaration past the line limit and the generated project
 * failed its own format check on the first build (§35).
 */
@Validated
@ConfigurationProperties(prefix = "demo")
data class ApplicationProperties(
    @field:NotBlank
    val environmentName: String,
    @field:Positive
    val maxWidgetsPerPage: Int,
)
