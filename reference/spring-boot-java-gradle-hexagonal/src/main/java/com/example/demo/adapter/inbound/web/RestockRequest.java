package com.example.demo.adapter.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

public record RestockRequest(@Positive @Schema(example = "5") int amount) {}
