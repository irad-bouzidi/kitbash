package com.example.demo.adapter.inbound.web

import com.example.demo.application.port.inbound.ManageWidgets
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * A driving adapter. It depends on [ManageWidgets] — the port — and has no idea that a class
 * called `WidgetService` exists.
 */
@RestController
@RequestMapping("/api/widgets")
@Tag(name = "Widgets", description = "The example resource this reference project is built around")
class WidgetController(
    private val widgets: ManageWidgets,
) {
    @GetMapping
    @Operation(summary = "List every widget")
    fun list(): List<WidgetResponse> = widgets.findAll().map(WidgetResponse::from)

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one widget")
    @ApiResponse(responseCode = "404", description = "No widget with that id")
    fun get(
        @PathVariable id: Long,
    ): WidgetResponse = WidgetResponse.from(widgets.findById(id))

    @PostMapping
    @Operation(summary = "Create a widget")
    @ApiResponse(responseCode = "409", description = "A widget with that name already exists")
    fun create(
        @Valid @RequestBody request: CreateWidgetRequest,
    ): ResponseEntity<WidgetResponse> {
        val created = WidgetResponse.from(widgets.create(request.name, request.quantity))
        return ResponseEntity.created(URI.create("/api/widgets/${created.id}")).body(created)
    }

    @PostMapping("/{id}/restock")
    @Operation(summary = "Add to a widget's quantity")
    fun restock(
        @PathVariable id: Long,
        @Valid @RequestBody request: RestockRequest,
    ): WidgetResponse = WidgetResponse.from(widgets.restock(id, request.amount))

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a widget")
    fun delete(
        @PathVariable id: Long,
    ): ResponseEntity<Void> {
        widgets.delete(id)
        return ResponseEntity.noContent().build()
    }
}
