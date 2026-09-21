package com.example.demo.widget.internal

internal class DuplicateWidgetNameException(
    name: String,
) : RuntimeException("A widget named '$name' already exists")
