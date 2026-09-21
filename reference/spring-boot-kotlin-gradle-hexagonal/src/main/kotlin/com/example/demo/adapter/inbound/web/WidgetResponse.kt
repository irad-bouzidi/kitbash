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
    @field:Schema(example = "1")
    val id: Long?,
    @field:Schema(example = "flux capacitor")
    val name: String,
    @field:Schema(example = "3")
    val quantity: Int,
    val createdAt: Instant,
) {
    companion object {
        fun from(widget: Widget): WidgetResponse = WidgetResponse(widget.id, widget.name, widget.quantity, widget.createdAt)
    }
}
