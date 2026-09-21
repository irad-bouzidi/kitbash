package com.example.demo.adapter.inbound.web;

import com.example.demo.domain.Widget;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The wire shape, which is the adapter's business and not the domain's. It happens to have the
 * same fields today; the reason it is a separate type is that an HTTP contract and a domain model
 * change for different reasons and on different schedules.
 */
@Schema(description = "A widget as returned by the API")
public record WidgetResponse(
        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED) Long id,
        @Schema(example = "flux capacitor", requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED) int quantity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

    static WidgetResponse from(Widget widget) {
        return new WidgetResponse(widget.id(), widget.name(), widget.quantity(), widget.createdAt());
    }
}
