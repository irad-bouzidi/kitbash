package com.example.demo.adapter.inbound.web;

import com.example.demo.application.port.inbound.ManageWidgets;
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
 * A driving adapter. It depends on {@link ManageWidgets} — the port — and has no idea that a class
 * called {@code WidgetService} exists.
 */
@RestController
@RequestMapping("/api/widgets")
@Tag(name = "Widgets", description = "The example resource this reference project is built around")
public class WidgetController {

    private final ManageWidgets widgets;

    public WidgetController(ManageWidgets widgets) {
        this.widgets = widgets;
    }

    @GetMapping
    @Operation(summary = "List every widget")
    public List<WidgetResponse> list() {
        return widgets.findAll().stream().map(WidgetResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one widget")
    @ApiResponse(responseCode = "404", description = "No widget with that id")
    public WidgetResponse get(@PathVariable long id) {
        return WidgetResponse.from(widgets.findById(id));
    }

    @PostMapping
    @Operation(summary = "Create a widget")
    @ApiResponse(responseCode = "409", description = "A widget with that name already exists")
    public ResponseEntity<WidgetResponse> create(@Valid @RequestBody CreateWidgetRequest request) {
        WidgetResponse created = WidgetResponse.from(widgets.create(request.name(), request.quantity()));
        return ResponseEntity.created(URI.create("/api/widgets/" + created.id()))
                .body(created);
    }

    @PostMapping("/{id}/restock")
    @Operation(summary = "Add to a widget's quantity")
    public WidgetResponse restock(@PathVariable long id, @Valid @RequestBody RestockRequest request) {
        return WidgetResponse.from(widgets.restock(id, request.amount()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a widget")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        widgets.delete(id);
        return ResponseEntity.noContent().build();
    }
}
