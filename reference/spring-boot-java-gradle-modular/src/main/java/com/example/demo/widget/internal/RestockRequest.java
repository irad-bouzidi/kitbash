package com.example.demo.widget.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

record RestockRequest(@Positive @Schema(example = "5") int amount) {}
