package com.example.demo.adapter.inbound.web

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class CreateWidgetRequest(
    @field:NotBlank
    @field:Size(max = 120)
    @field:Schema(example = "flux capacitor")
    val name: String,
    @field:PositiveOrZero
    @field:Schema(example = "3")
    val quantity: Int,
)
