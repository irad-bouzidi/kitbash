package com.example.demo.controller;

import com.example.demo.domain.Widget;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A widget as returned by the API")
public record WidgetResponse(
        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(example = "flux capacitor", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        int quantity,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

    public static WidgetResponse from(Widget widget) {
        return new WidgetResponse(widget.id(), widget.name(), widget.quantity(), widget.createdAt());
    }
}
