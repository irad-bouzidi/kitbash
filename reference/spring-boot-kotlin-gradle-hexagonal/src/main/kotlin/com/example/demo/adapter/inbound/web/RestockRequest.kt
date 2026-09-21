package com.example.demo.adapter.inbound.web

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

data class RestockRequest(
    @field:Positive
    @field:Schema(example = "5")
    val amount: Int,
)
