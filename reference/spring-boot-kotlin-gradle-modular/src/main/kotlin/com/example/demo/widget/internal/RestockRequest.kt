package com.example.demo.widget.internal

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

internal data class RestockRequest(
    @field:Positive
    @field:Schema(example = "5")
    val amount: Int,
)
