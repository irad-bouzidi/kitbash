package com.example.demo.controller

import com.example.demo.domain.Widget
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "A widget as returned by the API")
data class WidgetResponse(
    @field:Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long?,
    @field:Schema(example = "flux capacitor", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Schema(example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
    val quantity: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val createdAt: Instant,
) {
    companion object {
        fun from(widget: Widget): WidgetResponse = WidgetResponse(widget.id, widget.name, widget.quantity, widget.createdAt)
    }
}
