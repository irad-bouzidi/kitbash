package com.example.demo.domain

class DuplicateWidgetNameException(
    name: String,
) : RuntimeException("A widget named '$name' already exists")
