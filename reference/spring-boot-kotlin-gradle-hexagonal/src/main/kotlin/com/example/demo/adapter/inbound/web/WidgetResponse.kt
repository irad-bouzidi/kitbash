package com.example.demo.adapter.inbound.web

import com.example.demo.domain.Widget
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * The wire shape, which is the adapter's business and not the domain's. It happens to have the
 * same fields today; the reason it is a separate type is that an HTTP contract and a domain model
 * change for different reasons and on different schedules.
 */
@Schema(description = "A widget as returned by the API")
data class WidgetResponse(
    // Not `Long?`, although the entity's id is: a response is only ever built from a saved
    // widget. Declaring it nullable put `"null"` in the OpenAPI document's type for `id`, and
    // every generated client then made callers handle an id that cannot be absent (§36).
    @field:Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(example = "flux capacitor", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    val quantity: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val createdAt: Instant,
) {
    companion object {
        fun from(widget: Widget): WidgetResponse =
            WidgetResponse(
                requireNotNull(widget.id) { "an unsaved widget has nothing to return" },
                widget.name,
                widget.quantity,
                widget.createdAt,
            )
    }
}
