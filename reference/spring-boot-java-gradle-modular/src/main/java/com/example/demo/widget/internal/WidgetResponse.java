package com.example.demo.widget.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "A widget as returned by the API")
record WidgetResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "flux capacitor") String name,
        @Schema(example = "3") int quantity,
        Instant createdAt) {

    static WidgetResponse from(Widget widget) {
        return new WidgetResponse(widget.id(), widget.name(), widget.quantity(), widget.createdAt());
    }
}
