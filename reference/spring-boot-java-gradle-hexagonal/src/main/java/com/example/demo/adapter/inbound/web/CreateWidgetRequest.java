package com.example.demo.adapter.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateWidgetRequest(
        @NotBlank @Size(max = 120) @Schema(example = "flux capacitor") String name,
        @PositiveOrZero @Schema(example = "3") int quantity) {}
