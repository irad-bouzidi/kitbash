package com.example.demo.report.internal

import com.example.demo.widget.WidgetApi
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The second module, and the reason the first one's boundary means anything.
 *
 * A single-module "modular monolith" is a claim no test can check. This module exists to be a
 * consumer: it reads the widget module through [WidgetApi] and cannot see `widget.internal` at
 * all — not by convention, but because `ArchitectureTest` fails the build if it tries.
 *
 * It owns no tables. A module that needs another module's data asks for it; going to the database
 * directly is how a modular monolith becomes a distributed one with a shared schema.
 */
@RestController
@RequestMapping("/api/report")
@Tag(name = "Report", description = "A second module, reading the first one through its published API")
internal class ReportController(
    private val widgets: WidgetApi,
) {
    @GetMapping
    @Operation(summary = "Totals across every widget")
    fun stock(): StockReport {
        val quantities = widgets.summaries().map { it.quantity }
        return StockReport(quantities.size, quantities.sum())
    }
}
