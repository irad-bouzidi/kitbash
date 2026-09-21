package com.example.demo.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Application settings, bound and validated at startup. Binding failures are startup failures:
 * a misconfigured service should not accept its first request and then fall over.
 */
@Validated
@ConfigurationProperties(prefix = "demo")
data class DemoProperties(
    @field:NotBlank
    val environmentName: String,
    @field:Positive
    val maxWidgetsPerPage: Int,
)
