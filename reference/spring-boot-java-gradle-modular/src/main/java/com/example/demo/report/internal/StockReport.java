package com.example.demo.report.internal;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How much of everything there is")
record StockReport(@Schema(example = "3") int count, @Schema(example = "17") int totalQuantity) {}
