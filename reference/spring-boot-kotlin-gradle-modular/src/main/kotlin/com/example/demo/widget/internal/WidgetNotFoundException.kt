package com.example.demo.widget.internal

internal class WidgetNotFoundException(
    id: Long,
) : RuntimeException("No widget with id $id")
