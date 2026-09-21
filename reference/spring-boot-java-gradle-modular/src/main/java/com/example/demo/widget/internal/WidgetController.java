package com.example.demo.widget.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The module's HTTP surface. A module owns its endpoints the same way it owns its tables — there
 * is no shared `controller` package to put this in, which is the layout difference that makes a
 * modular monolith a modular monolith.
 */
@RestController
@RequestMapping("/api/widgets")
@Tag(name = "Widgets", description = "The example resource this reference project is built around")
class WidgetController {

    private final WidgetService widgets;

    WidgetController(WidgetService widgets) {
        this.widgets = widgets;
    }

    @GetMapping
    @Operation(summary = "List every widget")
    List<WidgetResponse> list() {
        return widgets.findAll().stream().map(WidgetResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one widget")
    @ApiResponse(responseCode = "404", description = "No widget with that id")
    WidgetResponse get(@PathVariable long id) {
        return WidgetResponse.from(widgets.findById(id));
    }

    @PostMapping
    @Operation(summary = "Create a widget")
    @ApiResponse(responseCode = "409", description = "A widget with that name already exists")
    ResponseEntity<WidgetResponse> create(@Valid @RequestBody CreateWidgetRequest request) {
        WidgetResponse created = WidgetResponse.from(widgets.create(request.name(), request.quantity()));
        return ResponseEntity.created(URI.create("/api/widgets/" + created.id()))
                .body(created);
    }

    @PostMapping("/{id}/restock")
    @Operation(summary = "Add to a widget's quantity")
    WidgetResponse restock(@PathVariable long id, @Valid @RequestBody RestockRequest request) {
        return WidgetResponse.from(widgets.restock(id, request.amount()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a widget")
    ResponseEntity<Void> delete(@PathVariable long id) {
        widgets.delete(id);
        return ResponseEntity.noContent().build();
    }
}
