package com.example.demo.controller

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

/**
 * `@field:` on every annotation, which is not decoration: a constructor parameter in Kotlin can
 * annotate the parameter, the property or the backing field, and Jakarta Validation only reads the
 * field. Without the prefix these constraints compile, ship and silently validate nothing.
 */
data class CreateWidgetRequest(
    @field:NotBlank
    @field:Size(max = 120)
    @field:Schema(example = "flux capacitor")
    val name: String,
    @field:PositiveOrZero
    @field:Schema(example = "3")
    val quantity: Int,
)
